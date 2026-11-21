package pluginprefs

import (
	"encoding/json"
	"testing"
)

const writerA = "AAECAwQFBgcICQoLDA0ODw"
const writerB = "EBESExQVFhcYGRobHB0eHw"

func TestIndependentPreferencesMergeAndReset(t *testing.T) {
	local, err := Empty().WithName("Org.plugin-a", "我的音乐", writerA)
	if err != nil {
		t.Fatal(err)
	}
	remote, _ := Empty().WithOrder([]string{"org.plugin-b", "Org.plugin-a"}, writerB)
	remote, _ = remote.WithEnabled("org.plugin-b", false, writerB)
	merged, err := local.Merge(remote)
	reversed, _ := remote.Merge(local)
	if err != nil || !merged.Equal(reversed) || merged.Names["Org.plugin-a"].Value != "我的音乐" || merged.Enabled["org.plugin-b"].Value || merged.Order.Value[0] != "org.plugin-b" {
		t.Fatalf("merge: %+v %v", merged, err)
	}
	reset, _ := merged.WithName("Org.plugin-a", "", writerA)
	reset, err = reset.Merge(local)
	if err != nil || reset.Names["Org.plugin-a"].Value != "" || reset.MaxClock() != 3 {
		t.Fatalf("reset resurrected: %+v %v", reset, err)
	}
	encoded, _ := json.Marshal(reset)
	var restored Document
	if json.Unmarshal(encoded, &restored) != nil || !restored.Equal(reset) {
		t.Fatal("document did not round trip")
	}
	if NewWriter() == NewWriter() {
		t.Fatal("writer identity reused")
	}
}

func TestRejectsInvalidPreferencesAndAmbiguousRegisters(t *testing.T) {
	valid, _ := Empty().WithName("plugin", "Local", writerA)
	other := valid.clone()
	other.Names["plugin"] = Register[string]{"Remote", 1, writerA}
	if _, err := valid.Merge(other); err == nil {
		t.Fatal("same stamp changed value")
	}
	for _, raw := range []string{
		`{"version":1,"names":{},"enabled":{"plugin":{"clock":1,"writer":"AAECAwQFBgcICQoLDA0ODw"}}}`,
		`{"version":1,"names":{},"enabled":null}`,
		`{"version":2,"names":{},"enabled":{}}`,
		`{"version":1,"names":{},"enabled":{},"order":{"value":["a","a"],"clock":1,"writer":"AAECAwQFBgcICQoLDA0ODw"}}`,
		`{"version":1,"names":{"bad..id":{"value":"Name","clock":1,"writer":"AAECAwQFBgcICQoLDA0ODw"}},"enabled":{}}`,
		`{"version":1,"names":{"plugin":{"value":"Name","clock":9007199254740992,"writer":"AAECAwQFBgcICQoLDA0ODw"}},"enabled":{}}`,
	} {
		var doc Document
		if json.Unmarshal([]byte(raw), &doc) == nil {
			t.Fatalf("accepted %s", raw)
		}
	}
}
