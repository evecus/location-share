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
}

func RegisterDevice(c *gin.Context) {
	userID := c.GetInt64("user_id")
	var req registerDeviceReq
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
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
		"note":         "Save device_token securely. It is shown only once.",
	})
}

// ListDevices: 登录用户可见全部已注册设备，并标注 access / online
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
		list[i].Access = st // none | pending | allowed
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
