package plugin

import "testing"

func TestKeywordHighlightBecomesPlainTitle(t *testing.T) {
	if got := plainTitle(`我懂此情<em class="keyword">入戏</em>为怀 1 < 2 > 0 <em>原文</em>`); got != `我懂此情入戏为怀 1 < 2 > 0 <em>原文</em>` {
		t.Fatal(got)
	}
}
