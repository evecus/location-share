package ws

import (
	"encoding/json"
	"log"
	"net/http"
	"strconv"
	"strings"
	"time"

	"location-share-server/auth"
	"location-share-server/db"
	"location-share-server/models"

	"github.com/gin-gonic/gin"
	"github.com/gorilla/websocket"
)

var upgrader = websocket.Upgrader{
	ReadBufferSize:  1024,
	WriteBufferSize: 1024,
	CheckOrigin: func(r *http.Request) bool {
		return true
	},
}

type Conn struct {
	*websocket.Conn
}

func ServeWS(hub *Hub, c *gin.Context) {
	token := c.Query("token")
	deviceToken := c.Query("device_token")
	if token == "" {
		h := c.GetHeader("Authorization")
		if strings.HasPrefix(h, "Bearer ") {
			token = strings.TrimPrefix(h, "Bearer ")
		}
	}
	if deviceToken == "" {
		deviceToken = c.GetHeader("X-Device-Token")
	}

	var client *Client

	if deviceToken != "" {
		dev, err := db.GetDeviceByToken(deviceToken)
		if err != nil || dev == nil {
			c.JSON(http.StatusUnauthorized, gin.H{"error": "invalid device token"})
			return
		}
		conn, err := upgrader.Upgrade(c.Writer, c.Request, nil)
		if err != nil {
			log.Println("upgrade:", err)
			return
		}
		client = &Client{
			Hub:      hub,
			Conn:     &Conn{conn},
			Send:     make(chan []byte, 64),
			DeviceID: dev.ID,
			IsDevice: true,
		}
	} else if token != "" {
		claims, err := auth.ParseToken(token)
		if err != nil || claims.Type != "user" {
			c.JSON(http.StatusUnauthorized, gin.H{"error": "invalid token"})
			return
		}
		conn, err := upgrader.Upgrade(c.Writer, c.Request, nil)
		if err != nil {
			log.Println("upgrade:", err)
			return
		}
		client = &Client{
			Hub:    hub,
			Conn:   &Conn{conn},
			Send:   make(chan []byte, 64),
			UserID: claims.UserID,
		}
	} else {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "token required"})
		return
	}

	hub.register <- client

	go client.writePump()
	go client.readPump()
}

func (c *Client) readPump() {
	defer func() {
		c.Hub.unregister <- c
		c.Conn.Close()
	}()
	c.Conn.SetReadLimit(4096)
	c.Conn.SetReadDeadline(time.Now().Add(60 * time.Second))
	c.Conn.SetPongHandler(func(string) error {
		c.Conn.SetReadDeadline(time.Now().Add(60 * time.Second))
		return nil
	})

	for {
		_, message, err := c.Conn.ReadMessage()
		if err != nil {
			if websocket.IsUnexpectedCloseError(err, websocket.CloseGoingAway, websocket.CloseAbnormalClosure) {
				log.Printf("ws read error: %v", err)
			}
			break
		}

		if c.IsDevice {
			c.handleDeviceMessage(message)
		} else {
			c.handleUserMessage(message)
		}
	}
}

func (c *Client) handleDeviceMessage(message []byte) {
	var base struct {
		Type string `json:"type"`
	}
	if err := json.Unmarshal(message, &base); err != nil {
		return
	}
	switch base.Type {
	case "location":
		var loc models.LocationMsg
		if err := json.Unmarshal(message, &loc); err != nil {
			return
		}
		loc.Type = "location"
		if loc.DeviceID == "" {
			loc.DeviceID = strconv.FormatInt(c.DeviceID, 10)
		}
		c.Hub.ForwardLocation(c.DeviceID, loc)
	case "pong", "heartbeat":
	default:
		log.Printf("unknown device msg type: %s", base.Type)
	}
}

func (c *Client) handleUserMessage(message []byte) {
	_ = message
}

func (c *Client) writePump() {
	ticker := time.NewTicker(30 * time.Second)
	defer func() {
		ticker.Stop()
		c.Conn.Close()
	}()
	for {
		select {
		case message, ok := <-c.Send:
			c.Conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
			if !ok {
				c.Conn.WriteMessage(websocket.CloseMessage, []byte{})
				return
			}
			if err := c.Conn.WriteMessage(websocket.TextMessage, message); err != nil {
				return
			}
		case <-ticker.C:
			c.Conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
			if err := c.Conn.WriteMessage(websocket.PingMessage, nil); err != nil {
				return
			}
		}
	}
}
