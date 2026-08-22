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
}

type Permission struct {
	ID              int64     `json:"id"`
	RequesterUserID int64     `json:"requester_user_id"`
	TargetDeviceID  int64     `json:"target_device_id"`
	Allowed         bool      `json:"allowed"`
	CreatedAt       time.Time `json:"created_at"`
}

type LocationMsg struct {
	Type      string  `json:"type"`
	DeviceID  string  `json:"device_id"`
	Lat       float64 `json:"lat"`
	Lon       float64 `json:"lon"`
	Accuracy  float64 `json:"accuracy"`
	Timestamp int64   `json:"timestamp"`
}

type CommandMsg struct {
	Type      string `json:"type"`
	DeviceID  string `json:"device_id"`
	RequestID string `json:"request_id,omitempty"`
}
