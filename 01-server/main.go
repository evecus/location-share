package main

import (
	"log"
	"os"

	"location-share-server/db"
	"location-share-server/handlers"
	"location-share-server/ws"

	"github.com/gin-gonic/gin"
	"github.com/joho/godotenv"
)

func main() {
	_ = godotenv.Load()

	if err := db.Init("location.db"); err != nil {
		log.Fatalf("failed to init db: %v", err)
	}
	defer db.Close()

	hub := ws.NewHub()
	go hub.Run()

	r := gin.Default()

	r.POST("/api/register", handlers.Register)
	r.POST("/api/login", handlers.Login)

	auth := r.Group("/api")
	auth.Use(handlers.AuthMiddleware())
	{
		auth.GET("/me", handlers.Me)
		auth.POST("/devices", handlers.RegisterDevice)
		auth.GET("/devices", handlers.ListDevices)
		auth.POST("/permissions", handlers.GrantPermission)
		auth.GET("/permissions", handlers.ListPermissions)
		// App 内授权流程
		auth.POST("/permissions/request", handlers.RequestPermission)
		auth.GET("/permissions/incoming", handlers.ListIncomingPermissions)
		auth.POST("/permissions/respond", handlers.RespondPermission)
		auth.POST("/location/request", handlers.RequestLocation)
	}

	device := r.Group("/api/device")
	device.Use(handlers.DeviceAuthMiddleware())
	{
		device.POST("/heartbeat", handlers.DeviceHeartbeat)
	}

	r.GET("/ws", func(c *gin.Context) {
		ws.ServeWS(hub, c)
	})

	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}
	log.Printf("server starting on :%s (db via env DATABASE_URL or SQLite)", port)
	if err := r.Run(":" + port); err != nil {
		log.Fatal(err)
	}
}
