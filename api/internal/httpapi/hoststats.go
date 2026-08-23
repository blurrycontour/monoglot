package httpapi

import (
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
)

// HostStats is a light view of the machine the server runs on.
//
// Read from /proc, which the container sees directly. No Docker socket, no
// extra privileges: the API cannot control the host, it just reads counters.
type HostStats struct {
	MemTotalBytes     int64   `json:"mem_total_bytes"`
	MemAvailableBytes int64   `json:"mem_available_bytes"`
	MemUsedPercent    float64 `json:"mem_used_percent"`
	CPUPercent        float64 `json:"cpu_percent"`
	CPUCores          int     `json:"cpu_cores"`
	Load1             float64 `json:"load1"`
	Available         bool    `json:"available"`
}

// CPU use is a delta between two samples of /proc/stat, so the previous
// reading has to be kept between requests.
var (
	cpuMu       sync.Mutex
	lastIdle    int64
	lastTotal   int64
	lastSampled time.Time
)

func readHostStats() HostStats {
	var h HostStats

	if mem, err := os.ReadFile("/proc/meminfo"); err == nil {
		vals := map[string]int64{}
		for _, line := range strings.Split(string(mem), "\n") {
			parts := strings.Fields(line)
			if len(parts) < 2 {
				continue
			}
			key := strings.TrimSuffix(parts[0], ":")
			if n, err := strconv.ParseInt(parts[1], 10, 64); err == nil {
				vals[key] = n * 1024 // /proc/meminfo is in kB
			}
		}
		h.MemTotalBytes = vals["MemTotal"]
		h.MemAvailableBytes = vals["MemAvailable"]
		if h.MemTotalBytes > 0 {
			used := h.MemTotalBytes - h.MemAvailableBytes
			h.MemUsedPercent = float64(used) / float64(h.MemTotalBytes) * 100
			h.Available = true
		}
	}

	if load, err := os.ReadFile("/proc/loadavg"); err == nil {
		if f := strings.Fields(string(load)); len(f) > 0 {
			h.Load1, _ = strconv.ParseFloat(f[0], 64)
		}
	}

	h.CPUCores = countCores()
	h.CPUPercent = sampleCPU()
	return h
}

func countCores() int {
	data, err := os.ReadFile("/proc/cpuinfo")
	if err != nil {
		return 0
	}
	n := 0
	for _, line := range strings.Split(string(data), "\n") {
		if strings.HasPrefix(line, "processor") {
			n++
		}
	}
	return n
}

// sampleCPU returns busy percentage since the previous call. The first call
// after startup has no baseline and reports 0 rather than a wrong number.
func sampleCPU() float64 {
	data, err := os.ReadFile("/proc/stat")
	if err != nil {
		return 0
	}
	line := ""
	for _, l := range strings.Split(string(data), "\n") {
		if strings.HasPrefix(l, "cpu ") {
			line = l
			break
		}
	}
	if line == "" {
		return 0
	}

	fields := strings.Fields(line)[1:]
	var total, idle int64
	for i, f := range fields {
		v, err := strconv.ParseInt(f, 10, 64)
		if err != nil {
			continue
		}
		total += v
		// Fields 3 and 4 are idle and iowait.
		if i == 3 || i == 4 {
			idle += v
		}
	}

	cpuMu.Lock()
	defer cpuMu.Unlock()

	prevTotal, prevIdle := lastTotal, lastIdle
	lastTotal, lastIdle, lastSampled = total, idle, time.Now()

	if prevTotal == 0 || total <= prevTotal {
		return 0
	}
	dTotal := float64(total - prevTotal)
	dIdle := float64(idle - prevIdle)
	pct := (dTotal - dIdle) / dTotal * 100
	if pct < 0 {
		return 0
	}
	if pct > 100 {
		return 100
	}
	return pct
}
