package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"math"
	"math/rand"
	"net/url"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"time"

	"github.com/gorilla/websocket"
)

type CommandMsg struct {
	Type      string `json:"type"`
	DeviceID  string `json:"device_id"`
	RequestID string `json:"request_id"`
}

type LocationOut struct {
	Type      string  `json:"type"`
	DeviceID  string  `json:"device_id"`
	Lat       float64 `json:"lat"`
	Lon       float64 `json:"lon"`
	Accuracy  float64 `json:"accuracy"`
	Timestamp int64   `json:"timestamp"`
	RequestID string  `json:"request_id,omitempty"`
}

func main() {
	server := flag.String("server", envOr("LS_SERVER", "ws://127.0.0.1:8080"), "WebSocket base")
	token := flag.String("token", envOr("LS_DEVICE_TOKEN", ""), "device_token")
	deviceID := flag.String("device-id", envOr("LS_DEVICE_ID", "1"), "device id")
	mock := flag.Bool("mock", envOr("LS_MOCK", "") == "1", "mock GPS")
	baseLat := flag.Float64("lat", 34.0522, "mock base lat")
	baseLon := flag.Float64("lon", -118.2437, "mock base lon")
	flag.Parse()
	if *token == "" {
		log.Fatal("device token required: -token or LS_DEVICE_TOKEN")
	}
	wsURL := strings.TrimRight(*server, "/") + "/ws?device_token=" + url.QueryEscape(*token)
	log.Printf("connecting to %s", maskToken(wsURL))
	for {
		if err := runSession(wsURL, *deviceID, *mock, *baseLat, *baseLon); err != nil {
			log.Printf("session ended: %v, reconnect in 5s", err)
			time.Sleep(5 * time.Second)
		}
	}
}

func runSession(wsURL, deviceID string, mock bool, baseLat, baseLon float64) error {
	dialer := websocket.Dialer{HandshakeTimeout: 15 * time.Second}
	conn, _, err := dialer.Dial(wsURL, nil)
	if err != nil {
		return err
	}
	defer conn.Close()
	log.Println("connected")
	conn.SetReadDeadline(time.Now().Add(90 * time.Second))
	conn.SetPongHandler(func(string) error {
		conn.SetReadDeadline(time.Now().Add(90 * time.Second))
		return nil
	})
	done := make(chan struct{})
	go func() {
		t := time.NewTicker(30 * time.Second)
		defer t.Stop()
		for {
			select {
			case <-done:
				return
			case <-t.C:
				_ = conn.WriteControl(websocket.PingMessage, nil, time.Now().Add(5*time.Second))
			}
		}
	}()
	defer close(done)
	for {
		_, msg, err := conn.ReadMessage()
		if err != nil {
			return err
		}
		var cmd CommandMsg
		if err := json.Unmarshal(msg, &cmd); err != nil {
			continue
		}
		if cmd.Type != "get_location" {
			continue
		}
		lat, lon, acc, err := getLocation(mock, baseLat, baseLon)
		if err != nil {
			log.Printf("get location failed: %v", err)
			continue
		}
		out := LocationOut{
			Type: "location", DeviceID: deviceID,
			Lat: lat, Lon: lon, Accuracy: acc,
			Timestamp: time.Now().Unix(), RequestID: cmd.RequestID,
		}
		data, _ := json.Marshal(out)
		if err := conn.WriteMessage(websocket.TextMessage, data); err != nil {
			return err
		}
		log.Printf("sent location: lat=%.6f lon=%.6f acc=%.1f", lat, lon, acc)
	}
}

func getLocation(mock bool, baseLat, baseLon float64) (lat, lon, acc float64, err error) {
	if mock {
		lat = baseLat + (rand.Float64()-0.5)*0.002
		lon = baseLon + (rand.Float64()-0.5)*0.002
		acc = 8 + rand.Float64()*10
		return lat, lon, acc, nil
	}
	if b, e := os.ReadFile("/data/local/tmp/ls_last_location.json"); e == nil {
		var m map[string]float64
		if json.Unmarshal(b, &m) == nil {
			if la, ok1 := m["lat"]; ok1 {
				if lo, ok2 := m["lon"]; ok2 {
					a := 15.0
					if v, ok := m["accuracy"]; ok {
						a = v
					}
					return la, lo, a, nil
				}
			}
		}
	}
	if out, e := exec.Command("dumpsys", "location").CombinedOutput(); e == nil {
		if la, lo, a, ok := parseDumpsysLocation(string(out)); ok {
			return la, lo, a, nil
		}
	}
	return 0, 0, 0, fmt.Errorf("no location source (use -mock or write /data/local/tmp/ls_last_location.json)")
}

func parseDumpsysLocation(s string) (lat, lon, acc float64, ok bool) {
	for _, line := range strings.Split(s, "\n") {
		line = strings.TrimSpace(line)
		if !strings.Contains(line, "location=") && !strings.Contains(line, "Location[") {
			continue
		}
		for i := 0; i < len(line)-5; i++ {
			if (line[i] >= '0' && line[i] <= '9' || line[i] == '-') && strings.Contains(line[i:], ",") {
				part := line[i:]
				end := strings.IndexAny(part, " ]\t")
				if end > 0 {
					part = part[:end]
				}
				bits := strings.Split(part, ",")
				if len(bits) >= 2 {
					la, e1 := strconv.ParseFloat(strings.TrimSpace(bits[0]), 64)
					lo, e2 := strconv.ParseFloat(strings.TrimSpace(bits[1]), 64)
					if e1 == nil && e2 == nil && math.Abs(la) <= 90 && math.Abs(lo) <= 180 {
						return la, lo, 20, true
					}
				}
			}
		}
	}
	return 0, 0, 0, false
}

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func maskToken(u string) string {
	if i := strings.Index(u, "device_token="); i >= 0 {
		return u[:i+13] + "***"
	}
	return u
}
