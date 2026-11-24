package player

import (
	"context"
	"errors"
	"math"
	"math/rand/v2"
	"os"
	"strings"
	"time"

	"github.com/egoist/mygo"
	"github.com/egoist/mygo/audio"

	"mygo-music/internal/i18n"
	"mygo-music/internal/lyrics"
	"mygo-music/internal/music"
)

type RepeatMode int

const (
	RepeatOff RepeatMode = iota
	RepeatAll
	RepeatOne
)

// Player plays a queue of tracks, from whichever source each is of. Its
// methods run on the main thread; loading happens on goroutines, whose results
// post brings back there.
type Player struct {
	post func(func())
	// Quiet is post without a redraw, for what mostly changes nothing, if set.
	Quiet func(func())
	// Source finds the source of an ID, or nil if it's gone.
	Source func(id string) music.Source
	// Failed, if set, hears that a source said the listener has something to
	// do first: ErrSignIn or ErrSetup.
	Failed func(source string, err error)
	// Quality returns the quality to ask sources for, as the settings say.
	Quality func() string
	// Normalize reports how to even out loudness, as the settings say.
	Normalize func() NormalizationMode
	// Cache keeps the songs played, if set.
	Cache *Cache
	// Changed, if set, hears that what plays, where it is or whether it
	// plays changed.
	Changed func()
	// Picked hears a new queue or an explicitly selected queue item. Restore,
	// transport controls and automatic advancement do not call it.
	Picked func()

	queue   []music.Track
	order   []int // the queue's indices in play order: shuffled or not
	at      int   // where in order the current track is, -1 for none
	shuffle bool
	repeat  RepeatMode
	volume  float64

	// A radio goes on with the tracks its feed gives, as the queue nears
	// its end, for as long as nothing else is played.
	radio      string
	feed       func(got func([]music.Track))
	pageFeed   func(got func([]music.Track, bool, error))
	radioGen   int  // counts radios, to drop what an old one gives
	feeding    bool // the feed is asked
	awaiting   bool // at the queue's end, for what the feed gives
	pageManual bool
	pageFailed bool

	track         *music.Track
	out           *audio.Player
	stream        stream
	watch         *watched      // stream, as out plays it
	info          *music.Stream // what the current track streams
	fade          fader
	gain          float64 // the current track's normalizing gain
	normalizer    *normalizer
	measuring     bool // a fixed gain is being measured, or has been measured
	loading       bool
	playing       bool // what the listener wants: false while paused
	videoHold     bool
	err           string
	trackProblems map[problemKey]music.TrackProblem
	gen           int // counts loads, to drop the results of stale ones
	stopOpening   context.CancelFunc

	lyrics       []lyrics.Line
	lyricsLoaded bool

	// pending holds a seek asked for while the track loads.
	pending time.Duration
	// A seek playing fades out, then lands, then fades in: meanwhile the
	// position is where it goes.
	seeking bool
	seekTo  time.Duration
	seekGen int

	// remote is set while the current track is a PlaybackRemote source's,
	// which plays it elsewhere: see remote.go.
	remote *remote
	// after runs fn after d, on another goroutine; tests replace it.
	after func(d time.Duration, fn func())
	now   func() time.Time
	cmds  commands
	// The volume last asked of a remote source, and whether that's on its
	// way: guarded by cmds.mu.
	volumeAsked  int
	volumeQueued bool

	recorded string // the track last told its source it played

	ahead *ahead // the next track's audio, asked for before its turn
}

func New(post func(func())) *Player {
	p := &Player{post: post, at: -1, volume: 0.8, gain: 1}
	p.after = func(d time.Duration, fn func()) { time.AfterFunc(d, fn) }
	p.now = time.Now
	// Music, mixed far enough ahead to play on while macOS keeps the app
	// waiting, as it does while its appearance changes.
	audio.SetLatency(audio.LatencyPlayback)
	// The media keys, and the controls of Control Center.
	mygo.NowPlaying.OnCommand(func(cmd mygo.MediaCommand, pos time.Duration) {
		switch cmd {
		case mygo.MediaPlay, mygo.MediaPause:
			if (cmd == mygo.MediaPlay) != p.playing {
				p.TogglePlay()
			}
		case mygo.MediaNext:
			p.Next()
		case mygo.MediaPrevious:
			p.Prev()
		case mygo.MediaSeek:
			p.Seek(pos)
		}
		p.post(func() {})
	})
	return p
}

// sync shows the system, and Changed, where playback is.
func (p *Player) sync() {
	if p.Changed != nil {
		defer p.Changed()
	}
	if p.track == nil {
		mygo.NowPlaying.Clear()
		return
	}
	state := mygo.PlaybackPaused
	if p.playing {
		state = mygo.PlaybackPlaying
	}
	mygo.NowPlaying.SetPlayback(state, p.Position())
}

// announce shows the system the track, and its cover once loaded.
func (p *Player) announce(t *music.Track, artwork []byte) {
	state := mygo.PlaybackPaused
	if p.playing {
		state = mygo.PlaybackPlaying
	}
	mygo.NowPlaying.Set(mygo.NowPlayingInfo{
		Title:    t.Name,
		Artist:   t.ArtistNames(),
		Album:    t.Album.Name,
		Artwork:  artwork,
		Duration: t.Length(),
		Position: p.Position(),
		State:    state,
		Commands: mygo.MediaPlay | mygo.MediaPause | mygo.MediaNext | mygo.MediaPrevious | mygo.MediaSeek,
	})
}

func (p *Player) ended() {
	if p.repeat == RepeatOne {
		p.out.Seek(0)
		if !p.videoHold {
			p.out.Play()
		}
		return
	}
	if p.at+1 >= len(p.order) && p.hasFeed() {
		p.awaitFeed()
		return
	}
	if p.at+1 >= len(p.order) && p.repeat == RepeatOff {
		p.playing = false
		p.sync()
		return
	}
	p.Next()
}

// PlayQueue replaces the queue with tracks and plays the one at start, or the
// first after it that can play.
func (p *Player) PlayQueue(tracks []music.Track, start int) {
	p.playQueue(tracks, start, forward)
}

// PlayTrack replaces the queue with tracks and plays the one at i, the
// listener's pick: one that can't play stays, saying why, rather than another
// playing in its place.
func (p *Player) PlayTrack(tracks []music.Track, i int) {
	p.playQueue(tracks, i, stay)
}

func (p *Player) PlayPaged(tracks []music.Track, index int, feed func(func([]music.Track, bool, error))) {
	if len(tracks) == 0 {
		return
	}
	if index < 0 {
		p.PlayShuffled(tracks)
	} else {
		p.PlayTrack(tracks, index)
	}
	p.pageFeed = feed
	p.refill()
}

// PlayShuffled replaces the queue with tracks in a random order, any of them
// first, and plays it with shuffle on.
func (p *Player) PlayShuffled(tracks []music.Track) {
	p.shuffle = true
	p.playQueue(tracks, -1, forward)
}

// playQueue plays tracks from start; a start below 0, when shuffled, lets
// any track come first.
func (p *Player) playQueue(tracks []music.Track, start, dir int) {
	if len(tracks) == 0 {
		return
	}
	p.endRadio()
	start = min(start, len(tracks)-1)
	p.queue = append([]music.Track(nil), tracks...)
	p.order = make([]int, len(p.queue))
	for i := range p.order {
		p.order[i] = i
	}
	if p.shuffle {
		p.shuffleOrder(start)
		start = 0
	}
	p.loadSkipping(max(start, 0), 0, dir, true, 0)
	if p.Picked != nil {
		p.Picked()
	}
}

// PlayRadio plays tracks in their order, then goes on with those feed
// gives as the queue nears its end, until something else is played. The
// radio's name says which it is. feed calls got on the main thread.
func (p *Player) PlayRadio(name string, tracks []music.Track, feed func(got func([]music.Track))) {
	if len(tracks) == 0 {
		return
	}
	shuffle := p.shuffle
	p.shuffle = false
	p.PlayQueue(tracks, 0)
	p.shuffle = shuffle
	p.AttachRadio(name, feed)
}

// AttachRadio makes the queue the radio name's, which feed goes on with,
// as after restoring a session that played it.
func (p *Player) AttachRadio(name string, feed func(got func([]music.Track))) {
	p.endRadio()
	p.radio, p.feed = name, feed
	p.refill()
}

// EndRadio stops the radio playing: the song playing plays on, but the
// songs the radio queued after it go, and it adds no more.
func (p *Player) EndRadio() {
	if p.radio == "" && p.feed == nil {
		return
	}
	awaiting := p.awaiting
	p.endRadio()
	if p.at >= 0 && p.at+1 < len(p.order) {
		kept := make([]music.Track, p.at+1)
		for i, k := range p.order[:p.at+1] {
			kept[i] = p.queue[k]
		}
		p.queue, p.order = kept, make([]int, len(kept))
		for i := range p.order {
			p.order[i] = i
		}
	}
	if awaiting {
		// At its end, waiting for more: nothing plays on.
		p.playing = false
		p.sync()
	}
}

func (p *Player) endRadio() {
	p.radioGen++
	p.radio, p.feed, p.feeding, p.awaiting = "", nil, false, false
	p.pageFeed = nil
	p.pageManual, p.pageFailed = false, false
}

func (p *Player) hasFeed() bool { return p.feed != nil || p.pageFeed != nil }

// Radio returns the name of the radio playing, or "".
func (p *Player) Radio() string { return p.radio }

func (p *Player) QueuePages() (more, loading, failed bool) {
	return p.pageFeed != nil, p.pageFeed != nil && p.feeding, p.pageFailed
}

func (p *Player) LoadNextQueuePage() {
	if p.pageFeed != nil {
		p.refillQueue(true)
	}
}

func (p *Player) refill() {
	p.refillQueue(false)
}

func (p *Player) refillQueue(requested bool) {
	if !p.hasFeed() || p.feeding || (!requested && (p.at < len(p.order)-2 || p.pageFailed && !p.awaiting)) {
		return
	}
	p.feeding = true
	p.pageFailed = false
	gen := p.radioGen
	if p.pageFeed != nil {
		p.pageFeed(func(tracks []music.Track, more bool, err error) {
			if gen != p.radioGen {
				return
			}
			p.feeding = false
			p.pageFailed = err != nil
			if err == nil && !more {
				p.pageFeed = nil
			}
			p.appendTracks(tracks)
			if !p.awaiting {
				if p.Changed != nil {
					p.Changed()
				}
				return
			}
			p.awaiting = false
			manual := p.pageManual
			p.pageManual = false
			switch {
			case p.at+1 < len(p.order):
				p.load(p.at + 1)
			case err != nil:
				p.playing, p.err = false, errorText(err)
				p.sync()
			case manual || p.repeat == RepeatAll:
				p.load(0)
			default:
				p.playing = false
				p.sync()
			}
		})
		return
	}
	p.feed(func(more []music.Track) {
		if gen != p.radioGen {
			return
		}
		p.feeding = false
		p.appendTracks(more)
		switch {
		case !p.awaiting:
		case p.at+1 < len(p.order):
			p.awaiting = false
			p.load(p.at + 1)
		default:
			// The radio has nothing more: it stops at its end.
			p.awaiting, p.playing = false, false
			p.err = i18n.T("The station has nothing more right now")
			p.sync()
		}
	})
}

// awaitFeed waits at the end of a radio's queue for what its feed gives.
func (p *Player) awaitFeed() {
	p.awaiting = true
	p.refill()
}

// appendTracks adds tracks to the end of the queue, but those in it.
func (p *Player) appendTracks(tracks []music.Track) {
	type key struct{ source, id string }
	in := make(map[key]bool, len(p.queue))
	for _, t := range p.queue {
		in[key{t.Source, t.ID}] = true
	}
	for _, t := range tracks {
		if k := (key{t.Source, t.ID}); !in[k] {
			in[k] = true
			p.order = append(p.order, len(p.queue))
			p.queue = append(p.queue, t)
		}
	}
}

// shuffleOrder puts the order in a random one with first at its head, or
// with any at its head when first is below 0.
func (p *Player) shuffleOrder(first int) {
	rand.Shuffle(len(p.order), func(i, j int) { p.order[i], p.order[j] = p.order[j], p.order[i] })
	for i, v := range p.order {
		if v == first {
			p.order[0], p.order[i] = p.order[i], p.order[0]
		}
	}
}

func (p *Player) ToggleShuffle() {
	p.shuffle = !p.shuffle
	if len(p.order) == 0 {
		return
	}
	cur := p.order[max(p.at, 0)]
	if p.shuffle {
		p.shuffleOrder(cur)
		p.at = 0
	} else {
		for i := range p.order {
			p.order[i] = i
		}
		p.at = cur
	}
}

func (p *Player) CycleRepeat() { p.repeat = (p.repeat + 1) % 3 }

// Current returns the track playing or loading, or nil.
func (p *Player) Current() *music.Track { return p.track }

// Queue returns the tracks queued, in the order they were given.
func (p *Player) Queue() []music.Track { return p.queue }

// Playing reports what the listener wants: false while paused.
func (p *Player) Playing() bool { return p.playing }

// Loading reports whether the current track is still loading.
// Loading reports whether the current track is on its way: loading, or
// waiting for the next of its bytes, after a seek past what has come in.
func (p *Player) Loading() bool {
	return p.loading || p.awaiting || p.watch != nil && p.watch.starved()
}

// Ready reports whether the current track has loaded, to play.
func (p *Player) Ready() bool { return p.out != nil || p.remote != nil && p.remote.started }

// Err returns why the current track did not play, or "".
func (p *Player) Err() string { return p.err }

// Stream returns what the current track streams, once loaded, or nil.
func (p *Player) Stream() *music.Stream { return p.info }

func (p *Player) Shuffle() bool      { return p.shuffle }
func (p *Player) Repeat() RepeatMode { return p.repeat }

// Lyrics returns the current track's lyrics, and whether they have loaded.
func (p *Player) Lyrics() ([]lyrics.Line, bool) { return p.lyrics, p.lyricsLoaded }

// Downloaded returns the share of the current track downloaded so far, or
// false before it has started to.
func (p *Player) Downloaded() (float64, bool) {
	if p.stream == nil {
		return 0, false
	}
	dl, _ := p.stream.source()
	return dl.Progress(), true
}

// PlayOrder returns the queue's indices in play order, and where the
// current track is among them, -1 for none.
func (p *Player) PlayOrder() ([]int, int) { return p.order, p.at }

func (p *Player) Next() {
	if len(p.order) == 0 {
		return
	}
	at := p.at + 1
	if at >= len(p.order) {
		if p.hasFeed() {
			p.pageManual = p.pageFeed != nil
			p.stopForFeed()
			p.awaitFeed()
			return
		}
		at = 0
	}
	p.load(at)
}

// Prev plays the track before this one, however far this one has played:
// once a few seconds in, going back to its start only kept the listener
// pressing twice.
func (p *Player) Prev() {
	if len(p.order) == 0 {
		return
	}
	at := p.at - 1
	if at < 0 {
		at = len(p.order) - 1
	}
	// One that can't play is passed going back, as going forward.
	p.loadSkipping(at, 0, back, true, 0)
}

// Jump plays the queue's track i, the listener's pick: one that can't play
// stays, saying why.
func (p *Player) Jump(i int) {
	for k, v := range p.order {
		if v == i {
			p.loadSkipping(k, 0, stay, true, 0)
			if p.Picked != nil {
				p.Picked()
			}
			return
		}
	}
}

// TogglePlay pauses, fading out, or plays, fading in.
func (p *Player) TogglePlay() {
	if p.awaiting && p.pageFeed != nil {
		p.awaiting, p.pageManual, p.playing = false, false, false
		p.sync()
		return
	}
	if p.remote != nil && p.remote.started {
		p.remoteToggle()
		return
	}
	if p.out == nil {
		switch {
		case p.loading:
			// It plays once loaded, or not.
			p.playing = !p.playing
			p.sync()
		case len(p.order) > 0:
			// Again, as for a track that couldn't play.
			p.loadSkipping(max(p.at, 0), 0, stay, true, p.pending)
		}
		return
	}
	out := p.out
	if p.playing {
		p.playing = false
		p.fade.fade(0, fadeOut, func() {
			p.post(func() {
				if p.out == out && !p.playing {
					out.Pause()
					p.sync()
				}
			})
		})
		p.sync()
		return
	}
	p.playing = true
	p.record()
	if !p.videoHold && !p.seeking && !out.Playing() {
		if out.Position() >= out.Duration()-time.Second {
			out.Seek(0)
		}
		out.Play()
	}
	p.fade.fade(1, fadeIn, nil)
	p.sync()
}

// seekGap is how long a seek waits for what plays to fade out, which the
// audio player does over 5 ms, as it pauses: within the next buffer or
// two it sends the system.
const seekGap = 30 * time.Millisecond

// Seek moves to t. Playing, it pauses and plays again around the jump,
// which the audio player fades over a few milliseconds, rather than
// cutting from one sound to the other with a click.
func (p *Player) Seek(t time.Duration) {
	if p.remote != nil && p.remote.started {
		p.remoteSeek(t)
		return
	}
	if p.out == nil {
		p.pending = t
		p.sync()
		return
	}
	out := p.out
	// One past what has come in waits for it: a frame once that would show.
	p.after(seekGap+starvedAfter+20*time.Millisecond, func() { p.post(func() {}) })
	// Paused, it jumps at once; but a seek on its way paused it only for
	// the jump, and this one takes its place, playing on from there.
	if !out.Playing() && !p.seeking {
		out.Seek(t)
		p.sync()
		return
	}
	p.seekGen++
	gen := p.seekGen
	p.seeking, p.seekTo = true, t
	out.Pause()
	time.AfterFunc(seekGap, func() {
		p.post(func() {
			if gen != p.seekGen || p.out != out {
				return
			}
			p.seeking = false
			out.Seek(t)
			if !p.videoHold && (p.playing || !p.fade.silent()) {
				out.Play()
			}
			p.sync()
		})
	})
	p.sync()
}

func (p *Player) HoldForVideo(hold bool) {
	if p.videoHold == hold {
		return
	}
	p.videoHold = hold
	if p.out == nil {
		return
	}
	if hold {
		p.out.Pause()
	} else if p.playing && !p.seeking {
		p.out.Play()
	}
}

func (p *Player) SetVolume(v float64) {
	p.volume = v
	p.applyGain()
	if p.remote != nil {
		p.remoteVolume(v)
	}
}

// applyGain sets the volume of what plays: the listener's, which the ear
// hears in powers, and the track's gain, if normalizing.
func (p *Player) applyGain() {
	g := p.volume * p.volume
	mode := p.normalizationMode()
	if mode == NormalizationStandard {
		g *= p.gain
		p.measureGain()
	}
	if p.normalizer != nil {
		p.normalizer.setMode(mode)
	}
	p.fade.setGain(g)
}

func (p *Player) normalizationMode() NormalizationMode {
	if p.Normalize != nil {
		return p.Normalize()
	}
	return NormalizationOff
}

// normPreamp lifts normalized tracks, which their gains bring to the quiet
// loudness of ReplayGain, nearer to how loud music plays elsewhere.
const normPreamp = 4

// normGain is the gain that brings a track as loud as others, as its source
// measured it, but never so loud that its peaks would clip.
func normGain(st *music.Stream) float64 {
	if st == nil || st.Gain == 0 && st.Peak == 0 {
		return 1
	}
	g := math.Pow(10, (st.Gain+normPreamp)/20)
	if st.Peak > 0 {
		g = min(g, 1/st.Peak)
	}
	return g
}

func (p *Player) Position() time.Duration {
	switch {
	case p.remote != nil && p.remote.started:
		return p.remotePosition()
	case p.out == nil:
		return p.pending
	case p.seeking:
		return p.seekTo
	}
	return p.out.Position()
}

func (p *Player) Duration() time.Duration {
	switch {
	case p.track == nil:
		return 0
	case p.remote != nil && p.remote.duration > 0:
		return p.remote.duration
	case p.track.Duration > 0 || p.out == nil:
		return p.track.Length()
	}
	return p.out.Duration() // a source that didn't say
}

// Levels returns the spectrum of what plays now.
func (p *Player) Levels() ([Bands]float32, bool) {
	if p.stream == nil || p.out == nil || !p.playing {
		return [Bands]float32{}, false
	}
	_, spec := p.stream.source()
	frame := int64(p.Position().Seconds() * float64(p.stream.SampleRate()))
	return spec.at(frame)
}

func (p *Player) stop() {
	if p.stopOpening != nil {
		p.stopOpening()
		p.stopOpening = nil
	}
	if out := p.out; out != nil {
		// Paused first, it fades out over a few milliseconds, rather than
		// stopping with a click.
		out.Pause()
		time.AfterFunc(100*time.Millisecond, out.Close)
	}
	p.out, p.stream, p.watch, p.info, p.remote = nil, nil, nil, nil, nil
	p.normalizer, p.measuring = nil, false
	p.seeking = false
	p.fade.attach(nil, 1)
}

// Which way a load goes past a track that can't play: on to the next, back to
// the one before, or nowhere, staying on it to say why.
const (
	forward = 1
	back    = -1
	stay    = 0
)

// load plays the track at order[at], skipping those that cannot play.
func (p *Player) load(at int) {
	p.loadSkipping(at, 0, forward, true, 0)
}

// stopForFeed stops what plays, at the end of a radio's queue, while its feed
// gives more.
func (p *Player) stopForFeed() {
	if was := p.remote; was != nil && was.started && p.playing {
		p.command(was.src, was.src.RemotePause)
	}
	p.stop()
}

// sourceOf returns the source of track t, or nil if it's gone.
func (p *Player) sourceOf(t *music.Track) music.Source {
	if p.Source == nil {
		return nil
	}
	return p.Source(t.Source)
}

// record tells the source of the current track, if it wants to know, that it
// plays, once for each time it's played.
func (p *Player) record() {
	if p.track == nil || !p.playing {
		return
	}
	src, ok := p.sourceOf(p.track).(music.Recorder)
	key := p.track.Source + "\x00" + p.track.ID
	if !ok || key == p.recorded {
		return
	}
	p.recorded = key
	t := *p.track
	go src.Played(t)
}

// prepare selects the track and its metadata without opening audio. Restored
// sessions can stay paused without holding a network stream open until Play.
func (p *Player) prepare(at int, play bool, from time.Duration) (music.Track, music.Source) {
	t := p.queue[p.order[at]]
	src := p.sourceOf(&t)
	if was := p.remote; was != nil && was.started && p.playing && (src == nil || src.Info().ID != was.src.Info().ID) {
		// Whatever plays next isn't its to play.
		p.command(was.src, was.src.RemotePause)
	}
	p.stop()
	p.gen++
	gen := p.gen
	p.at = at
	p.track = &t
	p.loading, p.playing, p.err = false, play, ""
	p.awaiting = false
	p.pageManual = false
	p.lyrics, p.lyricsLoaded = nil, false
	p.pending = from
	p.recorded = ""
	p.announce(&t, nil)
	p.refill()

	p.loadCover(gen, &t)
	p.loadLyrics(gen, src, t)
	return t, src
}

// loadSkipping loads the track at order[at], to play or not, from from,
// skipping those that cannot play the way dir goes: skipped of them so far.
func (p *Player) loadSkipping(at, skipped, dir int, play bool, from time.Duration) {
	p.loadAudio(at, skipped, dir, play, from, false)
}

func (p *Player) loadAudio(at, skipped, dir int, play bool, from time.Duration, refreshed bool) {
	t, src := p.prepare(at, play, from)
	gen := p.gen
	p.loading = true
	if src != nil && src.Info().Playback == music.PlaybackRemote {
		p.loadRemote(gen, src, at, skipped, dir, play, from)
		return
	}
	quality := music.Qualities[len(music.Qualities)/2]
	if p.Quality != nil {
		quality = p.Quality()
	}
	pre := p.takeAhead(t, quality)
	ctx, cancel := context.WithCancel(context.Background())
	p.stopOpening = cancel
	if refreshed && pre != nil {
		pre.close()
		pre = nil
	}
	recover := func(err error) bool {
		if !refreshMedia(err) {
			return false
		}
		position, intent := p.Position(), p.playing
		if refreshed {
			p.pending = position
			return false
		}
		p.loadAudio(at, 0, stay, intent, position, true)
		return true
	}
	go func() {
		info, s, err := p.openContext(ctx, src, t, quality, pre)
		p.post(func() {
			if gen != p.gen {
				if s != nil {
					s.Close()
				}
				return
			}
			cancel()
			p.stopOpening = nil
			p.loading = false
			if err != nil {
				if recover(err) {
					return
				}
				if refreshMedia(err) {
					dir = stay
				}
				p.loadFailed(t.Source, err, at, skipped, dir)
				return
			}
			p.rememberTrackProblem(&t, nil)
			p.stream, p.info = s, info
			p.gain = normGain(info)
			p.normalizer = newNormalizer(s)
			p.normalizer.setMode(p.normalizationMode())
			p.watch = &watched{
				stream: p.normalizer, now: p.now,
				ended: func() { p.post(func() {}) },
				failed: func(err error) {
					p.post(func() {
						if gen != p.gen || p.out == nil {
							return
						}
						if recover(err) {
							return
						}
						p.pending = p.Position()
						p.stop()
						p.playing, p.err = false, errorText(err)
						p.rememberTrackProblem(&t, err)
						p.sync()
					})
				},
			}
			out := audio.NewPlayer(p.watch)
			p.out = out
			// From its middle, as a session picked up again, it fades in.
			level := 1.0
			if p.pending > 0 {
				level = 0
			}
			p.fade.attach(p.out, level)
			p.applyGain()
			p.out.OnEnd(func() {
				p.post(func() {
					if gen == p.gen && p.out == out && p.playing {
						p.ended()
					}
				})
			})
			if p.pending > 0 {
				p.out.Seek(p.pending)
				p.pending = 0
			}
			if p.playing {
				if !p.videoHold {
					p.out.Play()
				}
				p.fade.fade(1, fadeIn, nil)
				p.record()
			}
			p.sync()
			p.getAhead(quality)
		})
	}()
}

// loadCover shows the system the cover of t, once it has it.
func (p *Player) loadCover(gen int, t *music.Track) {
	url := t.Album.ImageURL
	if url == "" {
		return
	}
	go func() {
		// The system shows it small, as Now Playing does.
		cover, err := music.Fetch(music.SizedImage(url, 600))
		if err == nil {
			p.post(func() {
				if gen == p.gen {
					p.announce(t, cover)
				}
			})
		}
	}()
}

// loadLyrics asks the source of t for its lyrics, if it has any.
func (p *Player) loadLyrics(gen int, src music.Source, t music.Track) {
	go func() {
		var lines []lyrics.Line
		if src != nil && src.Can("lyrics") {
			if l, err := src.Lyrics(t.ID); err == nil && l != nil {
				lines = lyrics.FromSource(l)
			}
		}
		p.post(func() {
			if gen == p.gen {
				p.lyrics = lines
				p.lyricsLoaded = true
				p.sync()
			}
		})
	}()
}

// loadFailed says why the track at order[at] can't play, and skips it the way
// dir goes, unless it stays, nothing in the queue can play, or the listener has
// to do something first, which would stop the next track too.
func (p *Player) loadFailed(source string, err error, at, skipped, dir int) {
	p.err = errorText(err)
	p.rememberTrackProblem(p.track, err)
	if errors.Is(err, music.ErrSignIn) || errors.Is(err, music.ErrSetup) {
		p.playing = false
		if p.Failed != nil {
			p.Failed(source, err)
		}
		p.sync()
		return
	}
	if dir != stay && skipped+1 < len(p.order) {
		next := (at + dir + len(p.order)) % len(p.order)
		p.loadSkipping(next, skipped+1, dir, p.playing, 0)
		return
	}
	p.playing = false
	p.sync()
}

// errorText says what went wrong with a track, in the listener's terms where
// the source's error is one the app knows.
func errorText(err error) string {
	switch {
	case errors.Is(err, errNoSource):
		return i18n.T("The plugin of this song is gone")
	case errors.Is(err, music.ErrUnavailable):
		return i18n.T("This song isn't available")
	case errors.Is(err, music.ErrVIP), errors.Is(err, music.ErrNoStream), errors.Is(err, music.ErrNetwork):
		return TrackProblemText(classifyTrackProblem(err))
	case errors.Is(err, music.ErrSignIn):
		return i18n.T("Sign in to play this song")
	case errors.Is(err, music.ErrSetup):
		if reason := music.Reason(err); reason != "" {
			return reason
		}
		return i18n.T("The plugin needs setting up")
	}
	return err.Error()
}

var errNoSource = errors.New("player: no such source")

// open opens track t's audio at quality: what was asked for ahead of it, if
// that worked; or kept in the cache, or else a file on disk, played from
// there, or else streamed into the cache, and kept once it has all come in.
// What failed ahead is asked for again: it may have been a moment's.
func (p *Player) open(src music.Source, t music.Track, quality string, pre *ahead) (*music.Stream, stream, error) {
	return p.openContext(context.Background(), src, t, quality, pre)
}

func (p *Player) openContext(ctx context.Context, src music.Source, t music.Track, quality string, pre *ahead) (*music.Stream, stream, error) {
	if src == nil {
		if pre != nil {
			pre.close()
		}
		return nil, nil, errNoSource
	}
	var dl *download
	var info *music.Stream
	ok := false
	if pre != nil {
		select {
		case <-ctx.Done():
			pre.close()
			return nil, nil, ctx.Err()
		case <-pre.done:
		}
		if pre.err == nil {
			info, dl, ok = pre.info, pre.dl, true
			if dl == nil {
				path, _ := music.PathOf(info.URL)
				var err error
				if dl, err = openFile(path); err != nil {
					return nil, nil, err
				}
			}
		}
	}
	if !ok {
		dl, info, ok = p.Cache.lookup(t.Source, t.ID, quality)
	}
	if !ok {
		var err error
		info, err = src.Stream(t.ID, quality)
		if err != nil {
			return nil, nil, err
		}
		if err := ctx.Err(); err != nil {
			return nil, nil, err
		}
		if info == nil || strings.TrimSpace(info.URL) == "" {
			return nil, nil, music.ErrNoStream
		}
		if path, isFile := music.PathOf(info.URL); isFile {
			dl, err = openFile(path)
			if err != nil {
				return nil, nil, err
			}
		} else {
			dl = p.Cache.download(t.Source, t.ID, quality, info)
		}
	}
	stop := context.AfterFunc(ctx, dl.cancel)
	s, err := openStream(dl, t.Duration)
	stop()
	if err != nil {
		dl.mu.Lock()
		if dl.err != nil {
			err = dl.err
		}
		dl.mu.Unlock()
		dl.Close()
		return nil, nil, err
	}
	return info, s, nil
}

// openFile opens a song on disk as a download that has all of it in.
func openFile(path string) (*download, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	info, err := f.Stat()
	if err != nil {
		f.Close()
		return nil, err
	}
	return opened(f, info.Size()), nil
}

// Session is where playback was left, to pick up again.
type Session struct {
	Queue    []music.Track `json:"queue"`
	Order    []int         `json:"order"`
	At       int           `json:"at"`
	Shuffle  bool          `json:"shuffle"`
	Repeat   RepeatMode    `json:"repeat"`
	Position int64         `json:"position"` // milliseconds
	Radio    string        `json:"radio,omitempty"`
}

// Session returns where playback is, or false with nothing queued.
func (p *Player) Session() (Session, bool) {
	if p.track == nil || p.at < 0 {
		return Session{}, false
	}
	return Session{
		Queue:    p.queue,
		Order:    p.order,
		At:       p.at,
		Shuffle:  p.shuffle,
		Repeat:   p.repeat,
		Position: p.Position().Milliseconds(),
		Radio:    p.radio,
	}, true
}

// Restore picks up a session where it was left, paused, with its cover and
// lyrics. Audio is opened once Play is asked, so network streams stay fresh.
func (p *Player) Restore(s Session) {
	n := len(s.Queue)
	if n == 0 || len(s.Order) != n || s.At < 0 || s.At >= n {
		return
	}
	seen := make([]bool, n)
	for _, i := range s.Order {
		if i < 0 || i >= n || seen[i] {
			return
		}
		seen[i] = true
	}
	p.queue, p.order = s.Queue, s.Order
	p.shuffle, p.repeat = s.Shuffle, s.Repeat%3
	p.prepare(s.At, false, time.Duration(s.Position)*time.Millisecond)
	p.sync()
}

// Audio is the current track's sound, opened apart from what plays by OpenAudio:
// interleaved stereo at its rate, read as it downloads.
type Audio interface {
	SampleRate() int
	Channels() int
	Read(dst []float32) (int, error)
	// SetPosition goes to a frame, of SampleRate a second.
	SetPosition(frame int64) error
	Close() error
}

// OpenAudio opens the current track's sound again, for decoding apart from what
// plays, as for transcribing an episode. It reads the same download, waiting for
// what has not come in, and fails once the track changes, or for a track that
// plays elsewhere. It works on any goroutine once opened; close it when done.
func (p *Player) OpenAudio() (Audio, error) {
	if p.stream == nil || p.track == nil {
		return nil, errors.New("player: nothing loaded")
	}
	dl, _ := p.stream.source()
	return openShared(dl, p.track.Duration)
}
