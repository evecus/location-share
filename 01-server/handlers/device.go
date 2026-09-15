package handlers

import (
	"crypto/rand"
	"encoding/hex"
	"net/http"

	"location-share-server/db"
	"location-share-server/ws"

	"github.com/gin-gonic/gin"
)

type registerDeviceReq struct {
	DeviceName string `json:"device_name" binding:"required,min=1,max=64"`
	// ReplaceExisting: 默认 true，同一账号只保留一台可分享设备，避免重复注册堆积
	ReplaceExisting *bool `json:"replace_existing"`
}

func RegisterDevice(c *gin.Context) {
	userID := c.GetInt64("user_id")
	var req registerDeviceReq
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	replace := true
	if req.ReplaceExisting != nil {
		replace = *req.ReplaceExisting
	}
	if replace {
		// 删除该用户旧设备（级联清理相关 permissions）
		_ = db.DeleteDevicesByUser(userID)
	}
	token, err := generateDeviceToken()
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "token gen failed"})
		return
	}
	dev, err := db.CreateDevice(userID, token, req.DeviceName)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{
		"device":       dev,
		"device_token": token,
		"note":         "Save device_token securely. It is shown only once. Previous devices of this account were replaced.",
	})
}

func ListDevices(c *gin.Context) {
	userID := c.GetInt64("user_id")
	list, err := db.ListAllDevices()
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	hub := ws.GetHub()
	for i := range list {
		list[i].Online = hub != nil && hub.IsDeviceOnline(list[i].ID)
		if list[i].UserID == userID {
			list[i].Access = "owner"
			continue
		}
		st, _ := db.GetPermissionStatus(userID, list[i].ID)
		list[i].Access = st
	}
	c.JSON(http.StatusOK, gin.H{"devices": list})
}

func DeviceHeartbeat(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{"status": "ok"})
}

func generateDeviceToken() (string, error) {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return hex.EncodeToString(b), nil
}
