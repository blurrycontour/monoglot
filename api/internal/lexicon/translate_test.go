package lexicon

import "testing"

func TestCleanTranslation(t *testing.T) {
	cases := map[string]string{
		`<bpt i="1" type="bold">{}</bpt>The Covid-19 pandemic<ept i="1">{}</ept>`: "The Covid-19 pandemic",
		"climate change;":             "climate change",
		"  the spread of infection  ": "the spread of infection",
		"plain":                       "plain",
	}
	for in, want := range cases {
		if got := cleanTranslation(in); got != want {
			t.Errorf("cleanTranslation(%q) = %q, want %q", in, got, want)
		}
	}
}
