package main

import (
	"encoding/json"
	"fmt"
	"html"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
)

func startConfigHTTP(cfg *Config, mu *sync.Mutex, reconnect chan struct{}) {
	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/" {
			http.NotFound(w, r)
			return
		}
		mu.Lock()
		c := *cfg
		mu.Unlock()
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		fmt.Fprint(w, configHTML(c, ""))
	})
	mux.HandleFunc("/save", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Redirect(w, r, "/", http.StatusSeeOther)
			return
		}
		_ = r.ParseForm()
		server := strings.TrimSpace(r.FormValue("server"))
		token := strings.TrimSpace(r.FormValue("token"))
		deviceID := strings.TrimSpace(r.FormValue("device_id"))
		mock := r.FormValue("mock") == "1" || r.FormValue("mock") == "on"

		if server == "" {
			server = "ws://127.0.0.1:8080"
		}
		if deviceID == "" {
			deviceID = "1"
		}

		mu.Lock()
		cfg.Server = server
		cfg.Token = token
		cfg.DeviceID = deviceID
		cfg.Mock = mock
		path := cfg.ConfigPath
		mu.Unlock()

		if err := saveConfigFile(path, server, token, deviceID, mock); err != nil {
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			fmt.Fprint(w, configHTML(*cfg, "保存失败: "+err.Error()))
			return
		}

		select {
		case reconnect <- struct{}{}:
		default:
		}

		mu.Lock()
		c := *cfg
		mu.Unlock()
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		fmt.Fprint(w, configHTML(c, "已保存，正在按新配置重连…"))
	})
	mux.HandleFunc("/status", func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		c := *cfg
		mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]interface{}{
			"server":    c.Server,
			"device_id": c.DeviceID,
			"has_token": c.Token != "",
			"mock":      c.Mock,
		})
	})

	log.Printf("starting config HTTP on %s", cfg.HTTPAddr)
	if err := http.ListenAndServe(cfg.HTTPAddr, mux); err != nil {
		log.Printf("config HTTP error: %v", err)
	}
}

func configHTML(c Config, msg string) string {
	mockChecked := ""
	if c.Mock {
		mockChecked = " checked"
	}
	msgHTML := ""
	if msg != "" {
		msgHTML = `<p style="color:#0a7;background:#e8f5e9;padding:10px;border-radius:8px;">` + html.EscapeString(msg) + `</p>`
	}
	return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width,initial-scale=1"/>
<title>位置共享 · 设备配置</title>
<style>
  body{font-family:-apple-system,sans-serif;max-width:420px;margin:24px auto;padding:0 16px;background:#f5f7fa;color:#202124}
  h1{font-size:1.25rem;margin-bottom:4px}
  .sub{color:#5f6368;font-size:.875rem;margin-bottom:20px}
  label{display:block;font-size:.8rem;color:#5f6368;margin:12px 0 4px}
  input[type=text]{width:100%;box-sizing:border-box;padding:12px;border:1px solid #dadce0;border-radius:8px;font-size:1rem}
  .row{display:flex;align-items:center;gap:8px;margin-top:12px}
  button{width:100%;margin-top:20px;padding:14px;background:#1a73e8;color:#fff;border:0;border-radius:8px;font-size:1rem;font-weight:600}
  .hint{font-size:.75rem;color:#5f6368;margin-top:16px;line-height:1.4}
  code{background:#e8eaed;padding:2px 6px;border-radius:4px}
</style>
</head>
<body>
  <h1>📍 位置共享 · 设备端</h1>
  <p class="sub">仅本机可访问 · 保存后自动重连服务器</p>
  ` + msgHTML + `
  <form method="POST" action="/save">
    <label>服务器 WebSocket 地址</label>
    <input type="text" name="server" value="` + html.EscapeString(c.Server) + `" placeholder="ws://192.168.1.10:8080 或 wss://api.example.com"/>
    <label>Device Token（注册设备时返回，不是用户密码）</label>
    <input type="text" name="token" value="` + html.EscapeString(c.Token) + `" placeholder="粘贴 device_token"/>
    <label>Device ID</label>
    <input type="text" name="device_id" value="` + html.EscapeString(c.DeviceID) + `" placeholder="1"/>
    <div class="row">
      <input type="checkbox" name="mock" id="mock" value="1"` + mockChecked + `/>
      <label for="mock" style="margin:0">使用模拟坐标（调试）</label>
    </div>
    <button type="submit">保存并重连</button>
  </form>
  <p class="hint">
    电脑浏览器访问：先执行<br/>
    <code>adb forward tcp:17890 tcp:17890</code><br/>
    再打开 <code>http://127.0.0.1:17890</code><br/><br/>
    手机本机浏览器：<code>http://127.0.0.1:17890</code>
  </p>
</body>
</html>`
}

func loadConfig(path string) *Config {
	c := &Config{Server: "ws://127.0.0.1:8080", DeviceID: "1"}
	data, err := os.ReadFile(path)
	if err != nil {
		return c
	}
	for _, line := range strings.Split(string(data), "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		parts := strings.SplitN(line, "=", 2)
		if len(parts) != 2 {
			continue
		}
		k := strings.TrimSpace(parts[0])
		v := strings.TrimSpace(parts[1])
		switch k {
		case "LS_SERVER":
			c.Server = v
		case "LS_DEVICE_TOKEN":
			c.Token = v
		case "LS_DEVICE_ID":
			c.DeviceID = v
		case "LS_MOCK":
			c.Mock = v == "1" || strings.EqualFold(v, "true")
		}
	}
	return c
}

func saveConfigFile(path, server, token, deviceID string, mock bool) error {
	if err := os.MkdirAll(filepath.Dir(path), 0755); err != nil {
		return err
	}
	mockS := "0"
	if mock {
		mockS = "1"
	}
	body := fmt.Sprintf(`# Location Share device config (edited via http://127.0.0.1:17890)
LS_SERVER=%s
LS_DEVICE_TOKEN=%s
LS_DEVICE_ID=%s
LS_MOCK=%s
`, server, token, deviceID, mockS)
	return os.WriteFile(path, []byte(body), 0600)
}
