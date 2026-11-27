package player

import (
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"reflect"
	"testing"

	"mygo-music/internal/music"
)

func TestRuntimeFailureAdvancesInShuffleOrderEvenWithRepeatOne(t *testing.T) {
	p, callbacks, _, tracks := servedQueue(t, "a", "b", "c")
	p.PlayTrack(tracks, 0)
	settle(t, callbacks, p)
	p.order, p.at, p.repeat = []int{0, 2, 1}, 0, RepeatOne
	p.watch.failed(errors.New("decoder failed"))
	eventually(t, "runtime failure did not advance", func() bool {
		select {
		case fn := <-callbacks:
			fn()
		default:
		}
		return p.Current().ID == "c" && p.Ready()
	})
	if !p.Playing() || p.TrackProblem(&tracks[0]) != music.TrackUnknown || !reflect.DeepEqual(p.Queue(), tracks) {
		t.Fatalf("playing=%v queue=%v problem=%v", p.Playing(), p.Queue(), p.TrackProblem(&tracks[0]))
	}
}

func TestAllTracksFailOnceAndExplicitSelectionCanRetry(t *testing.T) {
	p, callbacks, tracks := pickyQueue()
	defer p.stop()
	requests := 0
	p.Source = func(string) music.Source {
		return problemSource{resolve: func(string) (*music.Stream, error) {
			requests++
			return nil, music.ErrSignIn
		}}
	}
	p.repeat = RepeatOne
	p.PlayTrack(tracks, 0)
	settle(t, callbacks, p)
	if requests != len(tracks) || p.Playing() || p.Err() == "" {
		t.Fatalf("requests=%d playing=%v err=%q", requests, p.Playing(), p.Err())
	}
	p.Source = func(string) music.Source { return picky{ok: map[string]bool{"a": true}} }
	p.Jump(0)
	settle(t, callbacks, p)
	if !p.Playing() || p.Current().ID != "a" {
		t.Fatalf("retry: %s", p.Err())
	}
}

func TestPreloadFailurePreparesFollowingTrackAndSkipsFailedNext(t *testing.T) {
	for _, httpFailure := range []bool{false, true} {
		t.Run(map[bool]string{false: "source", true: "http"}[httpFailure], func(t *testing.T) {
			p, callbacks, src, tracks := servedQueue(t, "a", "b", "c")
			base := src.Stream
			if httpFailure {
				body, err := os.ReadFile("testdata/seek.mp3")
				if err != nil {
					t.Fatal(err)
				}
				server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
					if r.URL.Path == "/b" {
						w.WriteHeader(404)
						return
					}
					w.Write(body)
				}))
				defer server.Close()
				src.url = server.URL
			}
			p.Source = func(string) music.Source {
				return problemSource{resolve: func(id string) (*music.Stream, error) {
					if id == "b" && !httpFailure {
						return nil, music.ErrVIP
					}
					return base(id, "")
				}}
			}
			p.PlayTrack(tracks, 0)
			settle(t, callbacks, p)
			eventually(t, "failed next did not prepare c", func() bool {
				select {
				case fn := <-callbacks:
					fn()
				default:
				}
				if p.ahead == nil || p.ahead.id != "c" {
					return false
				}
				select {
				case <-p.ahead.done:
					return p.ahead.err == nil
				default:
					return false
				}
			})
			if p.Current().ID != "a" || !p.Playing() || p.Err() != "" {
				t.Fatal("preload stopped current audio")
			}
			p.ended()
			settle(t, callbacks, p)
			if p.Current().ID != "c" || !p.Playing() || src.count(src.asked, "c") != 1 {
				t.Fatalf("next=%s error=%s", p.Current().ID, p.Err())
			}
		})
	}
}

func TestPauseDuringFailedLoadDoesNotAdvance(t *testing.T) {
	p, callbacks, tracks := pickyQueue()
	defer p.stop()
	p.PlayTrack(tracks, 1)
	p.TogglePlay()
	settle(t, callbacks, p)
	if p.Playing() || p.Current().ID != "b" {
		t.Fatal("failed loading resumed after pause")
	}
}
