package ws

import (
	"encoding/json"
	"log"
	"sync"
	"time"

	"location-share-server/db"
	"location-share-server/models"
)

type Client struct {
	Hub      *Hub
	Conn     *Conn
	Send     chan []byte
	UserID   int64
	DeviceID int64
	IsDevice bool
}

type Hub struct {
	userClients   map[int64]map[*Client]bool
	deviceClients map[int64]*Client
	subs          map[string]int64
	register      chan *Client
	unregister    chan *Client
	broadcast     chan []byte
	mu            sync.RWMutex
}

var globalHub *Hub

func NewHub() *Hub {
	h := &Hub{
		userClients:   make(map[int64]map[*Client]bool),
		deviceClients: make(map[int64]*Client),
		subs:          make(map[string]int64),
		register:      make(chan *Client),
		unregister:    make(chan *Client),
		broadcast:     make(chan []byte),
	}
	globalHub = h
	return h
}

func GetHub() *Hub {
	return globalHub
}

func (h *Hub) Run() {
	for {
		select {
		case client := <-h.register:
			h.mu.Lock()
			if client.IsDevice {
				if old, ok := h.deviceClients[client.DeviceID]; ok {
					close(old.Send)
					delete(h.deviceClients, client.DeviceID)
				}
				h.deviceClients[client.DeviceID] = client
				log.Printf("device %d connected", client.DeviceID)
			} else {
				if h.userClients[client.UserID] == nil {
					h.userClients[client.UserID] = make(map[*Client]bool)
				}
				h.userClients[client.UserID][client] = true
				log.Printf("user %d connected", client.UserID)
			}
			h.mu.Unlock()

		case client := <-h.unregister:
			h.mu.Lock()
			if client.IsDevice {
				if cur, ok := h.deviceClients[client.DeviceID]; ok && cur == client {
					delete(h.deviceClients, client.DeviceID)
					close(client.Send)
					log.Printf("device %d disconnected", client.DeviceID)
				}
			} else {
				if clients, ok := h.userClients[client.UserID]; ok {
					if _, exists := clients[client]; exists {
						delete(clients, client)
						close(client.Send)
						if len(clients) == 0 {
							delete(h.userClients, client.UserID)
						}
						log.Printf("user %d disconnected", client.UserID)
					}
				}
			}
			h.mu.Unlock()
		}
	}
}

func (h *Hub) IsDeviceOnline(deviceID int64) bool {
	h.mu.RLock()
	defer h.mu.RUnlock()
	_, ok := h.deviceClients[deviceID]
	return ok
}

func (h *Hub) SendToDevice(deviceID int64, msg interface{}) error {
	h.mu.RLock()
	client, ok := h.deviceClients[deviceID]
	h.mu.RUnlock()
	if !ok {
		return errDeviceOffline
	}
	data, err := json.Marshal(msg)
	if err != nil {
		return err
	}
	select {
	case client.Send <- data:
		return nil
	case <-time.After(3 * time.Second):
		return errSendTimeout
	}
}

func (h *Hub) SubscribeLocation(userID, deviceID int64, requestID string) {
	h.mu.Lock()
	h.subs[requestID] = userID
	h.mu.Unlock()
	go func() {
		time.Sleep(60 * time.Second)
		h.mu.Lock()
		delete(h.subs, requestID)
		h.mu.Unlock()
	}()
}

func (h *Hub) ForwardLocation(fromDeviceID int64, loc models.LocationMsg) {
	// 补全设备展示信息
	if dev, err := db.GetDeviceByID(fromDeviceID); err == nil && dev != nil {
		if loc.DeviceID == "" {
			loc.DeviceID = db.DeviceIDToString(dev.ID)
		}
		loc.DeviceName = dev.DeviceName
		if u, err := db.GetUserByID(dev.UserID); err == nil && u != nil {
			loc.OwnerUsername = u.Username
		}
	}
	data, err := json.Marshal(loc)
	if err != nil {
		return
	}
	seen := map[int64]bool{}
	userIDs := make([]int64, 0, 4)

	h.mu.RLock()
	// 优先按 request_id 精确投递
	if loc.RequestID != "" {
		if uid, ok := h.subs[loc.RequestID]; ok {
			userIDs = append(userIDs, uid)
			seen[uid] = true
		}
	} else {
		for _, uid := range h.subs {
			if !seen[uid] {
				seen[uid] = true
				userIDs = append(userIDs, uid)
			}
		}
	}
	h.mu.RUnlock()

	if dev, err := db.GetDeviceByID(fromDeviceID); err == nil && dev != nil {
		if !seen[dev.UserID] {
			userIDs = append(userIDs, dev.UserID)
		}
	}

	h.mu.RLock()
	defer h.mu.RUnlock()
	for _, uid := range userIDs {
		if clients, ok := h.userClients[uid]; ok {
			for c := range clients {
				select {
				case c.Send <- data:
				default:
				}
			}
		}
	}
}

// NotifyUser 向某用户所有在线客户端推送任意 JSON 消息（如授权结果）
func (h *Hub) NotifyUser(userID int64, msg interface{}) {
	data, err := json.Marshal(msg)
	if err != nil {
		return
	}
	h.mu.RLock()
	defer h.mu.RUnlock()
	if clients, ok := h.userClients[userID]; ok {
		for c := range clients {
			select {
			case c.Send <- data:
			default:
			}
		}
	}
}

type hubError string

func (e hubError) Error() string { return string(e) }

const (
	errDeviceOffline hubError = "device offline"
	errSendTimeout   hubError = "send timeout"
)
