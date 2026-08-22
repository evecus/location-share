package handlers

import (
	"net/http"

	"location-share-server/db"

	"github.com/gin-gonic/gin"
)

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
