package httpapi

import (
	"strconv"
	"testing"
	"time"

	"github.com/blurrycontour/monoglot/api/internal/db"
)

// The "NEW" badge is decided on the client from discovered_at, so an episode
// aired long before the fetch must still report the fetch. Keying it off
// published_at meant an 8 Sidor article aired at 09:44 and pulled down at
// 10:21 was never marked new for anyone who had opened the app at 10:00.
func TestItemReportsWhenItWasDiscoveredNotAired(t *testing.T) {
	r := newRig(t)

	id := r.addItem("ready")
	aired := time.Now().Add(-72 * time.Hour).UTC().Truncate(time.Second)
	if _, err := r.pool.Exec(
		`UPDATE items SET published_at = ? WHERE id = ?`,
		db.FormatTime(aired), id); err != nil {
		t.Fatalf("backdate: %v", err)
	}

	var list struct {
		Items []ItemSummary `json:"items"`
	}
	r.get("/api/items?status=ready", &list)
	if len(list.Items) != 1 {
		t.Fatalf("want one item, got %d", len(list.Items))
	}
	it := list.Items[0]
	if it.DiscoveredAt == nil {
		t.Fatal("discovered_at missing from the list")
	}
	if !it.PublishedAt.Equal(aired) {
		t.Fatalf("published_at = %v, want %v", it.PublishedAt, aired)
	}
	if it.DiscoveredAt.Sub(aired) < 48*time.Hour {
		t.Fatalf("discovered_at %v tracks the airing, not the fetch", it.DiscoveredAt)
	}

	// The detail endpoint feeds the same model, so it has to carry the field
	// too or an item opened directly loses its badge state.
	var one struct {
		Item ItemSummary `json:"item"`
	}
	r.get("/api/items/"+strconv.Itoa(id), &one)
	if one.Item.DiscoveredAt == nil || !one.Item.DiscoveredAt.Equal(*it.DiscoveredAt) {
		t.Fatalf("detail discovered_at = %v, want %v", one.Item.DiscoveredAt, it.DiscoveredAt)
	}
}

// Archiving frees disk, it does not unhear the episode. A removed item stays
// in the library view in its own date section, re-fetchable in place, so it has
// to carry its progress across — without it the only record that you finished
// something is deleted along with its audio.
func TestArchivedItemKeepsItsProgress(t *testing.T) {
	r := newRig(t)

	id := r.addItem("ready")
	if _, err := r.pool.Exec(`
		INSERT INTO progress (item_id, position_ms, completed, listen_count)
		VALUES (?, ?, 1, 1)`, id, 900_000); err != nil {
		t.Fatalf("progress: %v", err)
	}

	postBody(t, r, "/api/items/"+strconv.Itoa(id)+"/archive", "")

	var list struct {
		Items []ItemSummary `json:"items"`
	}
	// It was fetched once, so it belongs in the library view, not the
	// never-fetched back catalogue.
	r.get("/api/items?status=library", &list)
	if len(list.Items) != 1 {
		t.Fatalf("want the removed item listed, got %d", len(list.Items))
	}
	got := list.Items[0]
	if got.Status != "archived" {
		t.Errorf("status = %q, want archived", got.Status)
	}
	if !got.Completed {
		t.Error("completed lost on archive: the finished tick cannot be drawn")
	}
	if got.PositionMS != 900_000 {
		t.Errorf("position_ms = %d, want 900000", got.PositionMS)
	}

	// And it must be absent from the back catalogue, which is only the
	// episodes that were never fetched.
	var archived struct {
		Items []ItemSummary `json:"items"`
	}
	r.get("/api/items?status=archived", &archived)
	if len(archived.Items) != 0 {
		t.Fatalf("removed-after-fetch item leaked into the back catalogue: %d", len(archived.Items))
	}
}
