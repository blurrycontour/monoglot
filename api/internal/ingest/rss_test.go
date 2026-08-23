package ingest

import "testing"

// Acast descriptions arrive as HTML; the app renders them as plain text.
func TestStripHTML(t *testing.T) {
	cases := map[string]string{
		"<p>Kan en AI få Nobelpriset?</p>":       "Kan en AI få Nobelpriset?",
		"En text<br />med radbrytning":           "En text med radbrytning",
		`<a href="https://x.se">Länk</a> i text`: `Länk i text`,
		"Redan &amp; ren text":                   "Redan & ren text",
		"  plain  ":                              "plain",
		"":                                       "",
		// Tags must not glue adjacent words together.
		"<p>ett</p><p>två</p>": "ett två",
	}
	for in, want := range cases {
		if got := stripHTML(in); got != want {
			t.Errorf("stripHTML(%q) = %q, want %q", in, got, want)
		}
	}
}
