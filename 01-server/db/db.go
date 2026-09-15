package db

import (
	"database/sql"
	"fmt"
	"os"
	"time"

	"location-share-server/models"

	_ "github.com/lib/pq"
	_ "modernc.org/sqlite"
)

var DB *sql.DB
var driverName string

func Init(sqlitePath string) error {
	dsn := os.Getenv("DATABASE_URL")
	if dsn == "" {
		dsn = os.Getenv("POSTGRES_DSN")
	}
	var err error
	if dsn != "" {
		driverName = "postgres"
		DB, err = sql.Open("postgres", dsn)
	} else {
		driverName = "sqlite"
		path := sqlitePath
		if path == "" {
			path = "location.db"
		}
		DB, err = sql.Open("sqlite", path+"?_pragma=foreign_keys(1)")
	}
	if err != nil {
		return err
	}
	if err = DB.Ping(); err != nil {
		return fmt.Errorf("db ping failed (%s): %w", driverName, err)
	}
	DB.SetMaxOpenConns(25)
	DB.SetMaxIdleConns(5)
	DB.SetConnMaxLifetime(5 * time.Minute)
	return migrate()
}

func Close() {
	if DB != nil {
		_ = DB.Close()
	}
}

func isPostgres() bool {
	return driverName == "postgres"
}

func migrate() error {
	var schema string
	if isPostgres() {
		schema = `
		CREATE TABLE IF NOT EXISTS users (
			id BIGSERIAL PRIMARY KEY,
			username TEXT NOT NULL UNIQUE,
			password_hash TEXT NOT NULL,
			created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
		);
		CREATE TABLE IF NOT EXISTS devices (
			id BIGSERIAL PRIMARY KEY,
			user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
			device_token TEXT NOT NULL UNIQUE,
			device_name TEXT NOT NULL,
			created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
		);
		CREATE TABLE IF NOT EXISTS permissions (
			id BIGSERIAL PRIMARY KEY,
			requester_user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
			target_device_id BIGINT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
			allowed BOOLEAN NOT NULL DEFAULT TRUE,
			created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
			UNIQUE(requester_user_id, target_device_id)
		);
		CREATE INDEX IF NOT EXISTS idx_devices_user ON devices(user_id);
		CREATE INDEX IF NOT EXISTS idx_perm_requester ON permissions(requester_user_id);
		CREATE INDEX IF NOT EXISTS idx_perm_device ON permissions(target_device_id);
		`
	} else {
		schema = `
		CREATE TABLE IF NOT EXISTS users (
			id INTEGER PRIMARY KEY AUTOINCREMENT,
			username TEXT NOT NULL UNIQUE,
			password_hash TEXT NOT NULL,
			created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
		);
		CREATE TABLE IF NOT EXISTS devices (
			id INTEGER PRIMARY KEY AUTOINCREMENT,
			user_id INTEGER NOT NULL,
			device_token TEXT NOT NULL UNIQUE,
			device_name TEXT NOT NULL,
			created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
			FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
		);
		CREATE TABLE IF NOT EXISTS permissions (
			id INTEGER PRIMARY KEY AUTOINCREMENT,
			requester_user_id INTEGER NOT NULL,
			target_device_id INTEGER NOT NULL,
			allowed INTEGER NOT NULL DEFAULT 1,
			created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
			UNIQUE(requester_user_id, target_device_id),
			FOREIGN KEY (requester_user_id) REFERENCES users(id) ON DELETE CASCADE,
			FOREIGN KEY (target_device_id) REFERENCES devices(id) ON DELETE CASCADE
		);
		CREATE INDEX IF NOT EXISTS idx_devices_user ON devices(user_id);
		CREATE INDEX IF NOT EXISTS idx_perm_requester ON permissions(requester_user_id);
		CREATE INDEX IF NOT EXISTS idx_perm_device ON permissions(target_device_id);
		`
	}
	_, err := DB.Exec(schema)
	return err
}

func CreateUser(username, passwordHash string) (*models.User, error) {
	var id int64
	var createdAt time.Time
	if isPostgres() {
		err := DB.QueryRow(`INSERT INTO users (username, password_hash) VALUES ($1, $2) RETURNING id, created_at`, username, passwordHash).Scan(&id, &createdAt)
		if err != nil {
			return nil, err
		}
	} else {
		res, err := DB.Exec(`INSERT INTO users (username, password_hash) VALUES (?, ?)`, username, passwordHash)
		if err != nil {
			return nil, err
		}
		id, _ = res.LastInsertId()
		createdAt = time.Now()
	}
	return &models.User{ID: id, Username: username, CreatedAt: createdAt}, nil
}

func GetUserByUsername(username string) (*models.User, error) {
	u := &models.User{}
	q := `SELECT id, username, password_hash, created_at FROM users WHERE username = ?`
	if isPostgres() {
		q = `SELECT id, username, password_hash, created_at FROM users WHERE username = $1`
	}
	err := DB.QueryRow(q, username).Scan(&u.ID, &u.Username, &u.PasswordHash, &u.CreatedAt)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	return u, err
}

func GetUserByID(id int64) (*models.User, error) {
	u := &models.User{}
	q := `SELECT id, username, password_hash, created_at FROM users WHERE id = ?`
	if isPostgres() {
		q = `SELECT id, username, password_hash, created_at FROM users WHERE id = $1`
	}
	err := DB.QueryRow(q, id).Scan(&u.ID, &u.Username, &u.PasswordHash, &u.CreatedAt)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	return u, err
}

func CreateDevice(userID int64, deviceToken, deviceName string) (*models.Device, error) {
	var id int64
	var createdAt time.Time
	if isPostgres() {
		err := DB.QueryRow(`INSERT INTO devices (user_id, device_token, device_name) VALUES ($1, $2, $3) RETURNING id, created_at`, userID, deviceToken, deviceName).Scan(&id, &createdAt)
		if err != nil {
			return nil, err
		}
	} else {
		res, err := DB.Exec(`INSERT INTO devices (user_id, device_token, device_name) VALUES (?, ?, ?)`, userID, deviceToken, deviceName)
		if err != nil {
			return nil, err
		}
		id, _ = res.LastInsertId()
		createdAt = time.Now()
	}
	return &models.Device{ID: id, UserID: userID, DeviceToken: deviceToken, DeviceName: deviceName, CreatedAt: createdAt}, nil
}

func GetDeviceByToken(token string) (*models.Device, error) {
	d := &models.Device{}
	q := `SELECT id, user_id, device_token, device_name, created_at FROM devices WHERE device_token = ?`
	if isPostgres() {
		q = `SELECT id, user_id, device_token, device_name, created_at FROM devices WHERE device_token = $1`
	}
	err := DB.QueryRow(q, token).Scan(&d.ID, &d.UserID, &d.DeviceToken, &d.DeviceName, &d.CreatedAt)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	return d, err
}

func GetDeviceByID(id int64) (*models.Device, error) {
	d := &models.Device{}
	q := `SELECT id, user_id, device_token, device_name, created_at FROM devices WHERE id = ?`
	if isPostgres() {
		q = `SELECT id, user_id, device_token, device_name, created_at FROM devices WHERE id = $1`
	}
	err := DB.QueryRow(q, id).Scan(&d.ID, &d.UserID, &d.DeviceToken, &d.DeviceName, &d.CreatedAt)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	return d, err
}

func ListDevicesByUser(userID int64) ([]models.Device, error) {
	q := `SELECT id, user_id, device_name, created_at FROM devices WHERE user_id = ?`
	if isPostgres() {
		q = `SELECT id, user_id, device_name, created_at FROM devices WHERE user_id = $1`
	}
	rows, err := DB.Query(q, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var list []models.Device
	for rows.Next() {
		var d models.Device
		if err := rows.Scan(&d.ID, &d.UserID, &d.DeviceName, &d.CreatedAt); err != nil {
			return nil, err
		}
		list = append(list, d)
	}
	return list, nil
}

func GrantPermission(requesterUserID, targetDeviceID int64) error {
	if isPostgres() {
		_, err := DB.Exec(`INSERT INTO permissions (requester_user_id, target_device_id, allowed) VALUES ($1, $2, TRUE) ON CONFLICT (requester_user_id, target_device_id) DO UPDATE SET allowed = TRUE`, requesterUserID, targetDeviceID)
		return err
	}
	_, err := DB.Exec(`INSERT INTO permissions (requester_user_id, target_device_id, allowed) VALUES (?, ?, 1) ON CONFLICT(requester_user_id, target_device_id) DO UPDATE SET allowed = 1`, requesterUserID, targetDeviceID)
	return err
}

func HasPermission(requesterUserID, targetDeviceID int64) (bool, error) {
	if isPostgres() {
		var allowed bool
		err := DB.QueryRow(`SELECT allowed FROM permissions WHERE requester_user_id = $1 AND target_device_id = $2 AND allowed = TRUE`, requesterUserID, targetDeviceID).Scan(&allowed)
		if err == sql.ErrNoRows {
			return false, nil
		}
		return allowed, err
	}
	var a int
	err := DB.QueryRow(`SELECT allowed FROM permissions WHERE requester_user_id = ? AND target_device_id = ? AND allowed = 1`, requesterUserID, targetDeviceID).Scan(&a)
	if err == sql.ErrNoRows {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	return a == 1, nil
}

func CanAccessDevice(requesterUserID, targetDeviceID int64) (bool, error) {
	dev, err := GetDeviceByID(targetDeviceID)
	if err != nil || dev == nil {
		return false, err
	}
	if dev.UserID == requesterUserID {
		return true, nil
	}
	return HasPermission(requesterUserID, targetDeviceID)
}

func ListPermissionsByRequester(userID int64) ([]models.Permission, error) {
	q := `SELECT id, requester_user_id, target_device_id, allowed, created_at FROM permissions WHERE requester_user_id = ?`
	if isPostgres() {
		q = `SELECT id, requester_user_id, target_device_id, allowed, created_at FROM permissions WHERE requester_user_id = $1`
	}
	rows, err := DB.Query(q, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var list []models.Permission
	for rows.Next() {
		var p models.Permission
		if isPostgres() {
			if err := rows.Scan(&p.ID, &p.RequesterUserID, &p.TargetDeviceID, &p.Allowed, &p.CreatedAt); err != nil {
				return nil, err
			}
		} else {
			var a int
			if err := rows.Scan(&p.ID, &p.RequesterUserID, &p.TargetDeviceID, &a, &p.CreatedAt); err != nil {
				return nil, err
			}
			p.Allowed = a == 1
		}
		list = append(list, p)
	}
	return list, nil
}

func ListDevicesAccessible(userID int64) ([]models.Device, error) {
	var q string
	if isPostgres() {
		q = `SELECT d.id, d.user_id, d.device_name, d.created_at FROM devices d WHERE d.user_id = $1 UNION SELECT d.id, d.user_id, d.device_name, d.created_at FROM devices d JOIN permissions p ON p.target_device_id = d.id WHERE p.requester_user_id = $1 AND p.allowed = TRUE`
	} else {
		q = `SELECT d.id, d.user_id, d.device_name, d.created_at FROM devices d WHERE d.user_id = ? UNION SELECT d.id, d.user_id, d.device_name, d.created_at FROM devices d JOIN permissions p ON p.target_device_id = d.id WHERE p.requester_user_id = ? AND p.allowed = 1`
	}
	var rows *sql.Rows
	var err error
	if isPostgres() {
		rows, err = DB.Query(q, userID)
	} else {
		rows, err = DB.Query(q, userID, userID)
	}
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var list []models.Device
	seen := map[int64]bool{}
	for rows.Next() {
		var d models.Device
		if err := rows.Scan(&d.ID, &d.UserID, &d.DeviceName, &d.CreatedAt); err != nil {
			return nil, err
		}
		if !seen[d.ID] {
			seen[d.ID] = true
			list = append(list, d)
		}
	}
	return list, nil
}

func DeviceIDToString(id int64) string {
	return fmt.Sprintf("%d", id)
}

// GetPermissionStatus returns: none | pending | allowed
func GetPermissionStatus(requesterUserID, targetDeviceID int64) (string, error) {
	if isPostgres() {
		var allowed bool
		err := DB.QueryRow(`SELECT allowed FROM permissions WHERE requester_user_id = $1 AND target_device_id = $2`, requesterUserID, targetDeviceID).Scan(&allowed)
		if err == sql.ErrNoRows {
			return "none", nil
		}
		if err != nil {
			return "none", err
		}
		if allowed {
			return "allowed", nil
		}
		return "pending", nil
	}
	var a int
	err := DB.QueryRow(`SELECT allowed FROM permissions WHERE requester_user_id = ? AND target_device_id = ?`, requesterUserID, targetDeviceID).Scan(&a)
	if err == sql.ErrNoRows {
		return "none", nil
	}
	if err != nil {
		return "none", err
	}
	if a == 1 {
		return "allowed", nil
	}
	return "pending", nil
}

// RequestPermission creates a pending row (allowed=0). Does not downgrade existing allowed.
func RequestPermission(requesterUserID, targetDeviceID int64) error {
	st, err := GetPermissionStatus(requesterUserID, targetDeviceID)
	if err != nil {
		return err
	}
	if st == "allowed" || st == "pending" {
		return nil
	}
	if isPostgres() {
		_, err = DB.Exec(`INSERT INTO permissions (requester_user_id, target_device_id, allowed) VALUES ($1, $2, FALSE) ON CONFLICT (requester_user_id, target_device_id) DO NOTHING`, requesterUserID, targetDeviceID)
		return err
	}
	_, err = DB.Exec(`INSERT INTO permissions (requester_user_id, target_device_id, allowed) VALUES (?, ?, 0) ON CONFLICT(requester_user_id, target_device_id) DO NOTHING`, requesterUserID, targetDeviceID)
	return err
}

// ListPendingForOwner returns pending requests targeting devices owned by ownerUserID
func ListPendingForOwner(ownerUserID int64) ([]models.Permission, error) {
	var q string
	if isPostgres() {
		q = `SELECT p.id, p.requester_user_id, p.target_device_id, p.allowed, p.created_at,
			u.username, d.device_name
			FROM permissions p
			JOIN devices d ON d.id = p.target_device_id
			JOIN users u ON u.id = p.requester_user_id
			WHERE d.user_id = $1 AND p.allowed = FALSE
			ORDER BY p.created_at DESC`
	} else {
		q = `SELECT p.id, p.requester_user_id, p.target_device_id, p.allowed, p.created_at,
			u.username, d.device_name
			FROM permissions p
			JOIN devices d ON d.id = p.target_device_id
			JOIN users u ON u.id = p.requester_user_id
			WHERE d.user_id = ? AND p.allowed = 0
			ORDER BY p.created_at DESC`
	}
	rows, err := DB.Query(q, ownerUserID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var list []models.Permission
	for rows.Next() {
		var p models.Permission
		if isPostgres() {
			if err := rows.Scan(&p.ID, &p.RequesterUserID, &p.TargetDeviceID, &p.Allowed, &p.CreatedAt, &p.RequesterUsername, &p.DeviceName); err != nil {
				return nil, err
			}
		} else {
			var a int
			if err := rows.Scan(&p.ID, &p.RequesterUserID, &p.TargetDeviceID, &a, &p.CreatedAt, &p.RequesterUsername, &p.DeviceName); err != nil {
				return nil, err
			}
			p.Allowed = a == 1
		}
		list = append(list, p)
	}
	return list, nil
}

// RespondPermission: owner accepts or denies a pending request
func RespondPermission(ownerUserID, permissionID int64, accept bool) error {
	// verify ownership
	var q string
	if isPostgres() {
		q = `SELECT p.id, p.requester_user_id, p.target_device_id, d.user_id
			FROM permissions p JOIN devices d ON d.id = p.target_device_id
			WHERE p.id = $1`
	} else {
		q = `SELECT p.id, p.requester_user_id, p.target_device_id, d.user_id
			FROM permissions p JOIN devices d ON d.id = p.target_device_id
			WHERE p.id = ?`
	}
	var pid, requesterID, deviceID, ownerID int64
	err := DB.QueryRow(q, permissionID).Scan(&pid, &requesterID, &deviceID, &ownerID)
	if err == sql.ErrNoRows {
		return fmt.Errorf("permission not found")
	}
	if err != nil {
		return err
	}
	if ownerID != ownerUserID {
		return fmt.Errorf("only device owner can respond")
	}
	if accept {
		return GrantPermission(requesterID, deviceID)
	}
	// deny: delete row
	if isPostgres() {
		_, err = DB.Exec(`DELETE FROM permissions WHERE id = $1`, permissionID)
	} else {
		_, err = DB.Exec(`DELETE FROM permissions WHERE id = ?`, permissionID)
	}
	return err
}

// ListAllDevices returns every device with owner username (for discovery in client)
func ListAllDevices() ([]models.Device, error) {
	var q string
	if isPostgres() {
		q = `SELECT d.id, d.user_id, d.device_name, d.created_at, u.username
			FROM devices d JOIN users u ON u.id = d.user_id ORDER BY d.id`
	} else {
		q = `SELECT d.id, d.user_id, d.device_name, d.created_at, u.username
			FROM devices d JOIN users u ON u.id = d.user_id ORDER BY d.id`
	}
	rows, err := DB.Query(q)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var list []models.Device
	for rows.Next() {
		var d models.Device
		if err := rows.Scan(&d.ID, &d.UserID, &d.DeviceName, &d.CreatedAt, &d.OwnerUsername); err != nil {
			return nil, err
		}
		list = append(list, d)
	}
	return list, nil
}

func DeleteDevicesByUser(userID int64) error {
	if isPostgres() {
		_, err := DB.Exec(`DELETE FROM devices WHERE user_id = $1`, userID)
		return err
	}
	_, err := DB.Exec(`DELETE FROM devices WHERE user_id = ?`, userID)
	return err
}
