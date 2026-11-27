package plugin

import "testing"

func TestReadOnlyLikesResolveAllPagesAndAccountChangesInvalidateSnapshot(t *testing.T) {
	p := load(t, plain, `
let who = "first", reads = 0;
export function account() { return who ? {id:who} : null; }
export function signOut() { who = ""; }
export function signIn() { who = "second"; return {id:who}; }
export function liked({offset}) { reads++; return !who ? {tracks:[],more:false} : offset === 0 ? {tracks:[{id:who}],more:true,nextOffset:5} : {tracks:[{id:"tail"}],more:false}; }
export function readCount() { return reads; }
`)
	if !p.Can("isLiked") || p.Can("like") {
		t.Fatal("read-only capability is wrong")
	}
	if _, err := p.Account(); err != nil {
		t.Fatal(err)
	}
	liked, err := p.IsLiked([]string{"first", "tail", "absent"})
	if err != nil || !liked["first"] || !liked["tail"] || liked["absent"] {
		t.Fatalf("%v %v", liked, err)
	}
	if _, err := p.IsLiked([]string{"first"}); err != nil {
		t.Fatal(err)
	}
	var reads int
	if err := p.call("readCount", nil, &reads); err != nil || reads != 2 {
		t.Fatalf("reads=%d err=%v", reads, err)
	}
	if err := p.SignOut(); err != nil {
		t.Fatal(err)
	}
	liked, err = p.IsLiked([]string{"first"})
	if err != nil || liked["first"] {
		t.Fatalf("old account survived: %v %v", liked, err)
	}
	if _, err := p.SignIn(); err != nil {
		t.Fatal(err)
	}
	liked, err = p.IsLiked([]string{"first", "second"})
	if err != nil || liked["first"] || !liked["second"] {
		t.Fatalf("new account: %v %v", liked, err)
	}
}

func TestBrokenLikedQueryFallsBackWithoutInventingNegativeState(t *testing.T) {
	for _, script := range []string{
		`export function isLiked(){throw new Error("query unavailable")}; export function liked(){return {tracks:[{id:"saved"}],more:false}}`,
		`export function isLiked(){return {}}; export function liked(){return {tracks:[{id:"saved"}],more:false}}`,
	} {
		p := load(t, plain, script)
		liked, err := p.IsLiked([]string{"saved"})
		if err != nil || !liked["saved"] {
			t.Fatalf("%v %v", liked, err)
		}
	}
	p := load(t, plain, `export function liked(){return {tracks:[{id:"same"}],more:true,nextOffset:1}}`)
	if _, err := p.IsLiked([]string{"unknown"}); err == nil {
		t.Fatal("incomplete list became unliked")
	}
	p = load(t, plain, `export function liked(){return {}}`)
	if _, err := p.IsLiked([]string{"unknown"}); err == nil {
		t.Fatal("invalid response became unliked")
	}
}
