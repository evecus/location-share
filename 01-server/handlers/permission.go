package handlers

import (
	"net/http"

	"location-share-server/db"
	"location-share-server/ws"

	"github.com/gin-gonic/gin"
)

// 旧接口：设备主人直接授权（兼容）
type grantPermReq struct {
	TargetDeviceID  int64 `json:"target_device_id" binding:"required"`
	RequesterUserID int64 `json:"requester_user_id" binding:"required"`
}

func GrantPermission(c *gin.Context) {
	userID := c.GetInt64("user_id")
	var req grantPermReq
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	dev, err := db.GetDeviceByID(req.TargetDeviceID)
	if err != nil || dev == nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "device not found"})
		return
	}
	if dev.UserID != userID {
		c.JSON(http.StatusForbidden, gin.H{"error": "only device owner can grant permission"})
		return
	}
	if err := db.GrantPermission(req.RequesterUserID, req.TargetDeviceID); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"message": "permission granted"})
}

func ListPermissions(c *gin.Context) {
	userID := c.GetInt64("user_id")
	list, err := db.ListPermissionsByRequester(userID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"permissions": list})
}

// 请求查看某设备位置的授权（发起方）
type requestPermReq struct {
	DeviceID int64 `json:"device_id" binding:"required"`
}

func RequestPermission(c *gin.Context) {
	userID := c.GetInt64("user_id")
	var req requestPermReq
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	dev, err := db.GetDeviceByID(req.DeviceID)
	if err != nil || dev == nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "device not found"})
		return
	}
	if dev.UserID == userID {
		c.JSON(http.StatusBadRequest, gin.H{"error": "cannot request permission for your own device"})
		return
	}
	status, err := db.GetPermissionStatus(userID, req.DeviceID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	if status == "allowed" {
		c.JSON(http.StatusOK, gin.H{"message": "already allowed", "status": "allowed"})
		return
	}
	if status == "pending" {
		c.JSON(http.StatusOK, gin.H{"message": "already pending", "status": "pending"})
		return
	}
	if err := db.RequestPermission(userID, req.DeviceID); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"message": "permission requested, waiting for owner", "status": "pending"})
}

// 设备主人：查看别人发给自己设备的待处理请求
func ListIncomingPermissions(c *gin.Context) {
	userID := c.GetInt64("user_id")
	list, err := db.ListPendingForOwner(userID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"requests": list})
}

// 设备主人：同意或拒绝
type respondPermReq struct {
	PermissionID int64 `json:"permission_id" binding:"required"`
	Accept       bool  `json:"accept"`
}

func RespondPermission(c *gin.Context) {
	userID := c.GetInt64("user_id")
	var req respondPermReq
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	// 回应前先查出请求方与设备，便于通知
	list, _ := db.ListPendingForOwner(userID)
	var requesterID int64
	var deviceID int64
	var deviceName string
	for _, p := range list {
		if p.ID == req.PermissionID {
			requesterID = p.RequesterUserID
			deviceID = p.TargetDeviceID
			deviceName = p.DeviceName
			break
		}
	}
	if err := db.RespondPermission(userID, req.PermissionID, req.Accept); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	// 实时通知申请方（若在线）
	if hub := ws.GetHub(); hub != nil && requesterID > 0 {
		if req.Accept {
			hub.NotifyUser(requesterID, gin.H{
				"type":            "permission_granted",
				"device_id":       deviceID,
				"device_name":     deviceName,
				"message":         "对方已同意你查看该设备位置",
			})
		} else {
			hub.NotifyUser(requesterID, gin.H{
				"type":      "permission_denied",
				"device_id": deviceID,
				"message":   "对方拒绝了你的授权请求",
			})
		}
	}
	if req.Accept {
		c.JSON(http.StatusOK, gin.H{"message": "permission granted"})
	} else {
		c.JSON(http.StatusOK, gin.H{"message": "permission denied"})
	}
}
