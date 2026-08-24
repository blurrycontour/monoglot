package httpapi

import (
	"fmt"
	"net/http"
	"strconv"
	"strings"
)

// The Monoglot mark: five rounded bars, a level meter at rest. Drawn rather
// than served from a file so it stays a single definition — the Android client
// draws the identical shape on a Canvas, and a PNG checked into the repo would
// drift from it silently.
//
// Unauthenticated, like the install page: a favicon that needs a bearer token
// is a favicon that never renders.
var iconHeights = []float64{0.34, 0.62, 1.0, 0.62, 0.34}

func (s *Server) appIcon(w http.ResponseWriter, r *http.Request) {
	size := 512.0
	if v := r.URL.Query().Get("size"); v != "" {
		if n, err := strconv.ParseFloat(v, 64); err == nil && n >= 16 && n <= 2048 {
			size = n
		}
	}

	color := "#7aa87a"
	if v := r.URL.Query().Get("color"); v != "" && isHexColor(v) {
		color = "#" + strings.TrimPrefix(v, "#")
	}
	background := r.URL.Query().Get("bg")
	if background != "" && !isHexColor(background) {
		background = ""
	}

	var b strings.Builder
	fmt.Fprintf(&b, `<svg xmlns="http://www.w3.org/2000/svg" width="%g" height="%g" `+
		`viewBox="0 0 %g %g" role="img" aria-label="Monoglot">`, size, size, size, size)
	if background != "" {
		fmt.Fprintf(&b, `<rect width="%g" height="%g" rx="%g" fill="#%s"/>`,
			size, size, size*0.22, strings.TrimPrefix(background, "#"))
	}

	// Bars occupy the middle 68% of the canvas so the mark keeps a margin when
	// it is used as an app icon, where the outer ring is often cropped.
	inner := size * 0.68
	originX := (size - inner) / 2
	slot := inner / float64(len(iconHeights))
	barWidth := slot * 0.46
	for i, h := range iconHeights {
		barHeight := inner * h
		x := originX + float64(i)*slot + (slot-barWidth)/2
		y := (size - barHeight) / 2
		fmt.Fprintf(&b, `<rect x="%.2f" y="%.2f" width="%.2f" height="%.2f" rx="%.2f" fill="%s"/>`,
			x, y, barWidth, barHeight, barWidth/2, color)
	}
	b.WriteString(`</svg>`)

	w.Header().Set("Content-Type", "image/svg+xml")
	w.Header().Set("Cache-Control", "public, max-age=86400")
	w.Write([]byte(b.String()))
}

func isHexColor(v string) bool {
	v = strings.TrimPrefix(v, "#")
	if len(v) != 3 && len(v) != 6 {
		return false
	}
	for _, r := range v {
		switch {
		case r >= '0' && r <= '9', r >= 'a' && r <= 'f', r >= 'A' && r <= 'F':
		default:
			return false
		}
	}
	return true
}
