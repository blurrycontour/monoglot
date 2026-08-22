package ingest

import "testing"

// Timestamps arrive from the worker as float seconds and must become integer
// milliseconds exactly; drift here shows up as off-by-one highlight bugs.
func TestToMS(t *testing.T) {
	cases := []struct {
		in   float64
		want int
	}{
		{0, 0},
		{0.12, 120},
		{0.7145, 715},
		{3.25, 3250},
		{299.9519, 299952},
		{-1, 0},
	}
	for _, c := range cases {
		if got := toMS(c.in); got != c.want {
			t.Errorf("toMS(%v) = %d, want %d", c.in, got, c.want)
		}
	}
}

func TestHasLetter(t *testing.T) {
	for _, s := range []string{"hus", "å", "14a"} {
		if !hasLetter(s) {
			t.Errorf("hasLetter(%q) = false, want true", s)
		}
	}
	for _, s := range []string{"", "14", "...", "—"} {
		if hasLetter(s) {
			t.Errorf("hasLetter(%q) = true, want false", s)
		}
	}
}
