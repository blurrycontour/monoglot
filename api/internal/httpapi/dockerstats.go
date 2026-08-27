package httpapi

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"os"
	"sort"
	"strings"
	"sync"
	"time"
)

// Per-container CPU and memory, read from the Docker Engine API over the unix
// socket. /proc inside the container reports the whole machine (and on a
// Proxmox guest, the hypervisor's machine), which answers "is the box busy"
// but never "which of my two services is using it".
//
// The socket is mounted read-only. That is worth being precise about: :ro
// stops the file being rewritten, it does NOT make the API read-only. Anything
// that can talk to this socket can create privileged containers and so control
// the host. The mitigation here is that the API only ever issues GETs and is
// never exposed off the LAN; a socket proxy would be the real fix if that
// changed.
const dockerSocket = "/var/run/docker.sock"

type ContainerStat struct {
	Name       string  `json:"name"`
	State      string  `json:"state"`
	Status     string  `json:"status"`
	CPUPercent float64 `json:"cpu_percent"`
	MemBytes   int64   `json:"mem_bytes"`
	MemLimit   int64   `json:"mem_limit"`
	MemPercent float64 `json:"mem_percent"`
}

// Sampling costs about a second per container: the daemon deliberately waits
// for a second collection so the CPU delta is meaningful. That second is not
// something a request may wait on — /api/system also carries the per-source
// counts the Listen tab draws its filter chips from, and those chips took two
// seconds to appear behind a CPU figure nothing on that screen shows. So the
// reading is served from cache and refreshed behind the response: a request
// sees the previous sample, never the one being taken.
var (
	dockerMu       sync.Mutex
	dockerCached   []ContainerStat
	dockerSampled  time.Time
	dockerSampling bool
	dockerCacheTTL = 15 * time.Second
)

var dockerClient = &http.Client{
	Timeout: 8 * time.Second,
	Transport: &http.Transport{
		DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
			var d net.Dialer
			return d.DialContext(ctx, "unix", dockerSocket)
		},
	},
}

func readContainerStats() []ContainerStat {
	dockerMu.Lock()
	out := dockerCached
	stale := time.Since(dockerSampled) >= dockerCacheTTL
	if stale && !dockerSampling {
		dockerSampling = true
		go refreshContainerStats()
	}
	dockerMu.Unlock()
	return out
}

// WarmContainerStats takes the first sample at startup, so the first System
// screen of a fresh server is not the one screen that shows no containers.
func WarmContainerStats() {
	dockerMu.Lock()
	if dockerSampling {
		dockerMu.Unlock()
		return
	}
	dockerSampling = true
	dockerMu.Unlock()
	go refreshContainerStats()
}

// refreshContainerStats samples in the background. It gets its own context:
// the request that noticed the staleness is long finished, and its context
// cancelled, by the time the daemon returns.
func refreshContainerStats() {
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	stats := sampleContainers(ctx)

	dockerMu.Lock()
	defer dockerMu.Unlock()
	dockerSampling = false
	// A failed sample must not blank a good reading: the daemon being briefly
	// unreachable is not the same as there being no containers. It does still
	// stamp the clock, or a daemon that is simply absent is re-sampled on
	// every request for the life of the process.
	dockerSampled = time.Now()
	if len(stats) > 0 || dockerCached == nil {
		dockerCached = stats
	}
}

type dockerContainer struct {
	ID     string            `json:"Id"`
	Names  []string          `json:"Names"`
	State  string            `json:"State"`
	Status string            `json:"Status"`
	Labels map[string]string `json:"Labels"`
}

func sampleContainers(ctx context.Context) []ContainerStat {
	var list []dockerContainer
	// Only this compose project's containers: anything else on the host is
	// somebody else's business and would just be noise on the screen.
	q := url.Values{}
	q.Set("filters", `{"label":["com.docker.compose.project"]}`)
	if err := dockerGet(ctx, "/containers/json?"+q.Encode(), &list); err != nil {
		return nil
	}

	self := composeProject(ctx)

	var wg sync.WaitGroup
	results := make([]ContainerStat, len(list))
	for i, c := range list {
		if self != "" && c.Labels["com.docker.compose.project"] != self {
			continue
		}
		wg.Add(1)
		go func(i int, c dockerContainer) {
			defer wg.Done()
			results[i] = containerStat(ctx, c)
		}(i, c)
	}
	wg.Wait()

	out := make([]ContainerStat, 0, len(results))
	for _, r := range results {
		if r.Name != "" {
			out = append(out, r)
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Name < out[j].Name })
	return out
}

// composeProject identifies which project this API belongs to, so a homelab
// running other stacks does not have them listed here.
func composeProject(ctx context.Context) string {
	var self struct {
		Config struct {
			Labels map[string]string `json:"Labels"`
		} `json:"Config"`
	}
	host, err := os.Hostname()
	if err != nil || host == "" {
		return ""
	}
	if err := dockerGet(ctx, "/containers/"+host+"/json", &self); err != nil {
		return ""
	}
	return self.Config.Labels["com.docker.compose.project"]
}

type dockerStats struct {
	CPUStats struct {
		CPUUsage struct {
			TotalUsage int64 `json:"total_usage"`
		} `json:"cpu_usage"`
		SystemUsage int64 `json:"system_cpu_usage"`
		OnlineCPUs  int   `json:"online_cpus"`
	} `json:"cpu_stats"`
	PreCPUStats struct {
		CPUUsage struct {
			TotalUsage int64 `json:"total_usage"`
		} `json:"cpu_usage"`
		SystemUsage int64 `json:"system_cpu_usage"`
	} `json:"precpu_stats"`
	MemoryStats struct {
		Usage int64            `json:"usage"`
		Limit int64            `json:"limit"`
		Stats map[string]int64 `json:"stats"`
	} `json:"memory_stats"`
}

func containerStat(ctx context.Context, c dockerContainer) ContainerStat {
	name := strings.TrimPrefix(strings.Join(c.Names, ""), "/")
	if svc := c.Labels["com.docker.compose.service"]; svc != "" {
		name = svc
	}
	out := ContainerStat{Name: name, State: c.State, Status: c.Status}
	if c.State != "running" {
		return out
	}

	var s dockerStats
	// stream=false without one-shot: the daemon returns the second collection,
	// so precpu_stats is populated and the CPU delta is real.
	if err := dockerGet(ctx, "/containers/"+c.ID+"/stats?stream=false", &s); err != nil {
		return out
	}

	// The docker CLI subtracts inactive_file; without it a container that has
	// merely read a lot of files looks like it is hoarding memory.
	used := s.MemoryStats.Usage - s.MemoryStats.Stats["inactive_file"]
	if used < 0 {
		used = s.MemoryStats.Usage
	}
	out.MemBytes = used
	out.MemLimit = s.MemoryStats.Limit
	if s.MemoryStats.Limit > 0 {
		out.MemPercent = float64(used) / float64(s.MemoryStats.Limit) * 100
	}

	dCPU := float64(s.CPUStats.CPUUsage.TotalUsage - s.PreCPUStats.CPUUsage.TotalUsage)
	dSys := float64(s.CPUStats.SystemUsage - s.PreCPUStats.SystemUsage)
	cores := s.CPUStats.OnlineCPUs
	if cores == 0 {
		cores = 1
	}
	if dCPU > 0 && dSys > 0 {
		out.CPUPercent = dCPU / dSys * float64(cores) * 100
	}
	return out
}

func dockerGet(ctx context.Context, path string, into any) error {
	// The version prefix is required by older daemons and harmless on new
	// ones; 1.41 is Docker 20.10, older than anything still running.
	req, err := http.NewRequestWithContext(ctx, http.MethodGet,
		"http://docker/v1.41"+path, nil)
	if err != nil {
		return err
	}
	resp, err := dockerClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("docker %s: %s", path, resp.Status)
	}
	return json.NewDecoder(resp.Body).Decode(into)
}
