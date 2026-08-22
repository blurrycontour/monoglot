package lexicon

import "testing"

func TestNormalize(t *testing.T) {
	cases := map[string]string{
		"Regeringen": "regeringen",
		"klartext,":  "klartext",
		"sätt.":      "sätt",
		"\"Hej\"":    "hej",
		"14.06":      "14.06",
		"—":          "",
		"  Du  ":     "du",
		"HUSEN!":     "husen",
		"e-post":     "e-post",
		"...":        "",
	}
	for in, want := range cases {
		if got := Normalize(in); got != want {
			t.Errorf("Normalize(%q) = %q, want %q", in, got, want)
		}
	}
}
