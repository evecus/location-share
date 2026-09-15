package models

import "time"

type User struct {
	ID           int64     `json:"id"`
	Username     string    `json:"username"`
	PasswordHash string    `json:"-"`
	CreatedAt    time.Time `json:"created_at"`
}

type Device struct {
	ID          int64     `json:"id"`
	UserID      int64     `json:"user_id"`
	DeviceToken string    `json:"device_token,omitempty"`
	DeviceName  string    `json:"device_name"`
	CreatedAt   time.Time `json:"created_at"`
	Online      bool      `json:"online,omitempty"`
	// 列表扩展字段（非表字段）
	OwnerUsername string `json:"owner_username,omitempty"`
	// access: owner | allowed | pending | none
	Access string `json:"access,omitempty"`
}

type Permission struct {
	ID              int64     `json:"id"`
	RequesterUserID int64     `json:"requester_user_id"`
	TargetDeviceID  int64     `json:"target_device_id"`
	Allowed         bool      `json:"allowed"`
	CreatedAt       time.Time `json:"created_at"`
	// 扩展
	RequesterUsername string `json:"requester_username,omitempty"`
	DeviceName        string `json:"device_name,omitempty"`
}

type LocationMsg struct {
	Type      string  `json:"type"`
	DeviceID  string  `json:"device_id"`
	Lat       float64 `json:"lat"`
	Lon       float64 `json:"lon"`
	Accuracy  float64 `json:"accuracy"`
	Timestamp int64   `json:"timestamp"`
	RequestID string  `json:"request_id,omitempty"`
}

type CommandMsg struct {
	Type      string `json:"type"`
	DeviceID  string `json:"device_id"`
	RequestID string `json:"request_id,omitempty"`
}
