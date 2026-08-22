package handlers

import (
	"net/http"
	"strconv"

	"location-share-server/db"
	"location-share-server/models"
	"location-share-server/ws"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

type requestLocationReq struct {
	DeviceID int64 `json:"device_id" binding:"required"`
}

func RequestLocation(c *gin.Context) {
	userID := c.GetInt64("user_id")
	var req requestLocationReq
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	ok, err := db.CanAccessDevice(userID, req.DeviceID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	if !ok {
		c.JSON(http.StatusForbidden, gin.H{"error": "no permission to access this device"})
		return
	}

	hub := ws.GetHub()
	if hub == nil {
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "ws hub not ready"})
		return
	}

	if !hub.IsDeviceOnline(req.DeviceID) {
		c.JSON(http.StatusNotFound, gin.H{"error": "device offline"})
		return
	}

	requestID := uuid.New().String()
	cmd := models.CommandMsg{
		Type:      "get_location",
		DeviceID:  strconv.FormatInt(req.DeviceID, 10),
		RequestID: requestID,
	}

	hub.SubscribeLocation(userID, req.DeviceID, requestID)

	if err := hub.SendToDevice(req.DeviceID, cmd); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to send command to device"})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"message":    "location request sent",
		"request_id": requestID,
		"device_id":  req.DeviceID,
	})
}
