package plugin

import "testing"

func TestArtistPagingUsesExistingExport(t *testing.T) {
	plug := load(t, `{"id":"author-pages"}`, `export function artist({id, offset}) {
  return {artist: {id}, tracks: [{id: id + ":" + offset, name: "Work"}], more: offset === 0, nextOffset: 25};
}`)
	first, err := plug.Artist("artist")
	if err != nil || len(first.Tracks) != 1 || first.Tracks[0].ID != "artist:0" || !first.More || first.NextOffset != 25 {
		t.Fatalf("first page: %+v, %v", first, err)
	}
	second, err := plug.ArtistPage("artist", first.NextOffset)
	if err != nil || len(second.Tracks) != 1 || second.Tracks[0].ID != "artist:25" || second.Tracks[0].Source != "author-pages" || second.More {
		t.Fatalf("second page: %+v, %v", second, err)
	}
}
