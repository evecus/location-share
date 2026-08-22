// Location Share Device Agent
// Local config UI: http://127.0.0.1:17890/
package main

import (
	"encoding/json"
	"flag"
	"log"
	"math"
	"math/rand"
	"net/url"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

const defaultConfigPort = "17890"

type Config struct {
	Server     string
	Token      string
	DeviceID   string
	Mock       bool
	ConfigPath string
	HTTPAddr   string
}

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
	cfgPath := flag.String("config", envOr("LS_CONFIG", "/data/adb/location_share/config"), "config file path")
	httpAddr := flag.String("http", envOr("LS_HTTP", "127.0.0.1:"+defaultConfigPort), "local config web UI")
	flag.Parse()

	cfg := loadConfig(*cfgPath)
	cfg.ConfigPath = *cfgPath
	cfg.HTTPAddr = *httpAddr

	if cfg.Server == "" {
		cfg.Server = envOr("LS_SERVER", "ws://127.0.0.1:8080")
	}
	if cfg.Token == "" {
		cfg.Token = envOr("LS_DEVICE_TOKEN", "")
	}
	if cfg.DeviceID == "" {
		cfg.DeviceID = envOr("LS_DEVICE_ID", "1")
	}
	if envOr("LS_MOCK", "") == "1" {
		cfg.Mock = true
	}

	var mu sync.Mutex
	reconnect := make(chan struct{}, 1)

	go startConfigHTTP(cfg, &mu, reconnect)
	log.Printf("config UI: http://%s/  (file: %s)", cfg.HTTPAddr, cfg.ConfigPath)

	for {
		mu.Lock()
		server := cfg.Server
		token := cfg.Token
		deviceID := cfg.DeviceID
		mock := cfg.Mock
		mu.Unlock()

		if token == "" {
			log.Printf("waiting for device_token via config UI http://%s/ ...", cfg.HTTPAddr)
			select {
			case <-reconnect:
			case <-time.After(10 * time.Second):
			}
			continue
		}

		wsURL := strings.TrimRight(server, "/") + "/ws?device_token=" + url.QueryEscape(token)
		log.Printf("connecting to %s", maskToken(wsURL))

		errCh := make(chan error, 1)
		go func() {
			errCh <- runSession(wsURL, deviceID, mock, 34.0522, -118.2437)
		}()

		select {
		case err := <-errCh:
			log.Printf("session ended: %v, reconnect in 5s", err)
			time.Sleep(5 * time.Second)
		case <-reconnect:
			log.Printf("config changed, reconnecting...")
			time.Sleep(500 * time.Millisecond)
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
	defer close(done)
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

	for {
		_, data, err := conn.ReadMessage()
		if err != nil {
			return err
		}
		var cmd CommandMsg
		if err := json.Unmarshal(data, &cmd); err != nil {
			continue
		}
		if cmd.Type != "get_location" {
			continue
		}
		lat, lon, acc := getLocation(mock, baseLat, baseLon)
		out := LocationOut{
			Type: "location", DeviceID: deviceID,
			Lat: lat, Lon: lon, Accuracy: acc,
			Timestamp: time.Now().Unix(), RequestID: cmd.RequestID,
		}
		b, _ := json.Marshal(out)
		if err := conn.WriteMessage(websocket.TextMessage, b); err != nil {
			return err
		}
		log.Printf("sent location lat=%.6f lon=%.6f", lat, lon)
	}
}

func getLocation(mock bool, baseLat, baseLon float64) (lat, lon, acc float64) {
	if mock {
		lat = baseLat + (rand.Float64()-0.5)*0.002
		lon = baseLon + (rand.Float64()-0.5)*0.002
		acc = 5 + rand.Float64()*10
		return
	}
	if lat, lon, acc, ok := readAndroidLocation(); ok {
		return lat, lon, acc
	}
	return baseLat, baseLon, 50
}

func readAndroidLocation() (lat, lon, acc float64, ok bool) {
	out, err := exec.Command("dumpsys", "location").CombinedOutput()
	if err != nil {
		return 0, 0, 0, false
	}
	s := string(out)
	lat = parseCoord(s, "Latitude:")
	lon = parseCoord(s, "Longitude:")
	if lat == 0 && lon == 0 {
		return 0, 0, 0, false
	}
	acc = 20
	if math.Abs(lat) > 90 || math.Abs(lon) > 180 {
		return 0, 0, 0, false
	}
	return lat, lon, acc, true
}

func parseCoord(s, key string) float64 {
	i := strings.Index(s, key)
	if i < 0 {
		return 0
	}
	rest := strings.TrimSpace(s[i+len(key):])
	fields := strings.Fields(rest)
	if len(fields) == 0 {
		return 0
	}
	v, err := strconv.ParseFloat(strings.Trim(fields[0], ","), 64)
	if err != nil {
		return 0
	}
	return v
}

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func maskToken(u string) string {
	if i := strings.Index(u, "device_token="); i >= 0 {
		return u[:i+12] + "***"
	}
	return u
}
