package main

import (
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha1"
	"crypto/subtle"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"math/big"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

type event struct {
	ID   int64           `json:"id"`
	Type string          `json:"type"`
	Data json.RawMessage `json:"data,omitempty"`
}
type participant struct {
	token        string
	lastSeen     time.Time
	events       []event
	next         int64
	wake         chan struct{}
	seen         []string
	queuedBytes  int
	signalCount  int
	signalWindow time.Time
}
type room struct {
	host, guest *participant
	created     time.Time
	sharer      string
	ended       bool
}
type iceServer struct {
	URLs       []string `json:"urls"`
	Username   string   `json:"username,omitempty"`
	Credential string   `json:"credential,omitempty"`
}
type rateEntry struct {
	count int
	since time.Time
}
type server struct {
	mu         sync.Mutex
	rooms      map[string]*room
	rates      map[string]rateEntry
	stun       []string
	turn       []string
	turnSecret string
	now        func() time.Time
}

func newServer() *server {
	return &server{rooms: map[string]*room{}, rates: map[string]rateEntry{}, now: time.Now}
}
func randomToken() string {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return base64.RawURLEncoding.EncodeToString(b)
}
func newParticipant(now time.Time) *participant {
	return &participant{token: randomToken(), lastSeen: now, wake: make(chan struct{})}
}
func (p *participant) emit(kind string, data any) {
	b, _ := json.Marshal(data)
	p.next++
	p.events = append(p.events, event{p.next, kind, b})
	p.queuedBytes += len(b)
	for len(p.events) > 256 || (p.queuedBytes > 256*1024 && len(p.events) > 1) {
		p.queuedBytes -= len(p.events[0].Data)
		p.events = p.events[1:]
	}
	close(p.wake)
	p.wake = make(chan struct{})
}
func respond(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}
func failure(w http.ResponseWriter, status int, message string) {
	respond(w, status, map[string]string{"error": message})
}
func readJSON(w http.ResponseWriter, r *http.Request, v any) bool {
	r.Body = http.MaxBytesReader(w, r.Body, 128*1024)
	if err := json.NewDecoder(r.Body).Decode(v); err != nil {
		failure(w, 400, "请求内容无效")
		return false
	}
	return true
}
func (s *server) limited(r *http.Request) bool {
	// Deliberately use the actual peer, never trust a caller-supplied X-Forwarded-For.
	host, _, _ := net.SplitHostPort(r.RemoteAddr)
	now := s.now()
	e := s.rates[host]
	if now.Sub(e.since) >= time.Minute {
		e = rateEntry{since: now}
	}
	e.count++
	s.rates[host] = e
	return e.count > 30
}
func (s *server) credentials(role string) []iceServer {
	result := []iceServer{}
	if len(s.stun) > 0 {
		result = append(result, iceServer{URLs: s.stun})
	}
	if len(s.turn) > 0 && s.turnSecret != "" {
		// Coturn REST credentials expire after 3 hours; room lifetime is capped at 2 hours.
		username := fmt.Sprintf("%d:%s:%s", s.now().Add(3*time.Hour).Unix(), role, randomToken()[:12])
		mac := hmac.New(sha1.New, []byte(s.turnSecret))
		_, _ = mac.Write([]byte(username))
		result = append(result, iceServer{s.turn, username, base64.StdEncoding.EncodeToString(mac.Sum(nil))})
	}
	return result
}
func (s *server) sessionResponse(id, role string, p *participant) any {
	return map[string]any{"roomId": id, "role": role, "token": p.token, "iceServers": s.credentials(role), "hasTurn": len(s.turn) > 0 && s.turnSecret != ""}
}
func (s *server) routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) { respond(w, 200, map[string]string{"status": "ok"}) })
	mux.HandleFunc("POST /v1/rooms", s.create)
	mux.HandleFunc("POST /v1/rooms/{room}/join", s.join)
	mux.HandleFunc("GET /v1/rooms/{room}/events", s.poll)
	mux.HandleFunc("POST /v1/rooms/{room}/signal", s.relay)
	mux.HandleFunc("POST /v1/rooms/{room}/share", s.share)
	mux.HandleFunc("DELETE /v1/rooms/{room}", s.leave)
	return mux
}
func (s *server) create(w http.ResponseWriter, r *http.Request) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.limited(r) {
		failure(w, 429, "操作太频繁，请稍后重试")
		return
	}
	if len(s.rooms) >= 1000 {
		failure(w, 503, "服务繁忙，请稍后重试")
		return
	}
	var id string
	for {
		n, err := rand.Int(rand.Reader, big.NewInt(100_000_000))
		if err != nil {
			failure(w, 500, "无法创建房间")
			return
		}
		id = fmt.Sprintf("%08d", n.Int64())
		if s.rooms[id] == nil {
			break
		}
	}
	p := newParticipant(s.now())
	s.rooms[id] = &room{host: p, created: s.now()}
	respond(w, 201, s.sessionResponse(id, "host", p))
}
func (s *server) join(w http.ResponseWriter, r *http.Request) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.limited(r) {
		failure(w, 429, "操作太频繁，请稍后重试")
		return
	}
	id := r.PathValue("room")
	rm := s.rooms[id]
	if rm == nil || s.now().Sub(rm.host.lastSeen) > 75*time.Second || s.now().Sub(rm.created) > 2*time.Hour {
		failure(w, 404, "房间不存在或已过期")
		return
	}
	if rm.ended {
		failure(w, 404, "房间已结束")
		return
	}
	if rm.guest != nil {
		failure(w, 409, "房间已有两人")
		return
	}
	rm.guest = newParticipant(s.now())
	rm.host.emit("peer-joined", nil)
	respond(w, 200, s.sessionResponse(id, "guest", rm.guest))
}
func (s *server) auth(r *http.Request) (*room, *participant, *participant, string) {
	rm := s.rooms[r.PathValue("room")]
	if rm == nil {
		return nil, nil, nil, ""
	}
	token := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
	if subtle.ConstantTimeCompare([]byte(token), []byte(rm.host.token)) == 1 {
		return rm, rm.host, rm.guest, "host"
	}
	if rm.guest != nil && subtle.ConstantTimeCompare([]byte(token), []byte(rm.guest.token)) == 1 {
		return rm, rm.guest, rm.host, "guest"
	}
	return nil, nil, nil, ""
}
func (s *server) poll(w http.ResponseWriter, r *http.Request) {
	after, err := strconv.ParseInt(r.URL.Query().Get("after"), 10, 64)
	if err != nil || after < 0 {
		failure(w, 400, "事件序号无效")
		return
	}
	timer := time.NewTimer(20 * time.Second)
	defer timer.Stop()
	for {
		s.mu.Lock()
		_, p, _, _ := s.auth(r)
		if p == nil {
			s.mu.Unlock()
			failure(w, 401, "会话已结束，请重新加入")
			return
		}
		p.lastSeen = s.now()
		if after > p.next || (len(p.events) > 0 && after < p.events[0].ID-1) {
			s.mu.Unlock()
			failure(w, 410, "会话状态已过期，请重新加入")
			return
		}
		events := []event{}
		for _, e := range p.events {
			if e.ID > after {
				events = append(events, e)
			}
		}
		wake := p.wake
		s.mu.Unlock()
		if len(events) > 0 {
			respond(w, 200, map[string]any{"events": events})
			return
		}
		select {
		case <-r.Context().Done():
			return
		case <-timer.C:
			respond(w, 200, map[string]any{"events": []event{}})
			return
		case <-wake:
		}
	}
}
func (s *server) relay(w http.ResponseWriter, r *http.Request) {
	var msg struct {
		ID   string          `json:"id"`
		Type string          `json:"type"`
		Data json.RawMessage `json:"data"`
	}
	if !readJSON(w, r, &msg) {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	rm, p, other, role := s.auth(r)
	if p == nil {
		failure(w, 401, "会话已结束")
		return
	}
	if rm.ended {
		failure(w, 410, "通话已结束")
		return
	}
	if len(msg.ID) > 64 {
		failure(w, 400, "消息标识过长")
		return
	}
	if msg.ID != "" {
		for _, id := range p.seen {
			if id == msg.ID {
				respond(w, 200, map[string]bool{"ok": true})
				return
			}
		}
	}
	if other == nil {
		failure(w, 409, "对方尚未加入")
		return
	}
	if s.now().Sub(p.signalWindow) >= time.Minute {
		p.signalWindow = s.now()
		p.signalCount = 0
	}
	p.signalCount++
	if p.signalCount > 300 {
		failure(w, 429, "连接消息过多，请稍后重试")
		return
	}
	switch msg.Type {
	case "offer":
		if role != "host" {
			failure(w, 403, "只有房主可以发起协商")
			return
		}
	case "answer", "restart":
		if role != "guest" {
			failure(w, 403, "信令角色错误")
			return
		}
	case "ice":
	default:
		failure(w, 400, "不支持的消息")
		return
	}
	p.lastSeen = s.now()
	if msg.ID != "" {
		p.seen = append(p.seen, msg.ID)
		if len(p.seen) > 256 {
			p.seen = p.seen[1:]
		}
	}
	other.emit(msg.Type, msg.Data)
	respond(w, 200, map[string]bool{"ok": true})
}
func (s *server) share(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Enabled bool `json:"enabled"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	rm, p, other, role := s.auth(r)
	if p == nil {
		failure(w, 401, "会话已结束")
		return
	}
	if rm.ended {
		failure(w, 410, "通话已结束")
		return
	}
	if req.Enabled {
		if other == nil {
			failure(w, 409, "请等待对方加入")
			return
		}
		if rm.sharer != "" && rm.sharer != role {
			failure(w, 409, "请先让对方停止共享")
			return
		}
		rm.sharer = role
	} else if rm.sharer == role {
		rm.sharer = ""
	} else {
		respond(w, 200, map[string]bool{"ok": true})
		return
	}
	p.lastSeen = s.now()
	data := map[string]string{"sharer": rm.sharer}
	p.emit("share-state", data)
	if other != nil {
		other.emit("share-state", data)
	}
	respond(w, 200, map[string]bool{"ok": true})
}
func (s *server) leave(w http.ResponseWriter, r *http.Request) {
	s.mu.Lock()
	defer s.mu.Unlock()
	rm, p, other, _ := s.auth(r)
	if p == nil {
		failure(w, 401, "会话已结束")
		return
	}
	rm.ended = true
	// Keep the terminal event briefly so a pending/reconnecting poll can retrieve it.
	p.emit("ended", nil)
	if other != nil {
		other.emit("ended", nil)
	}
	rm.created = s.now().Add(-2 * time.Hour)
	respond(w, 200, map[string]bool{"ok": true})
}
func (s *server) sweep() {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := s.now()
	for id, rm := range s.rooms {
		stale := now.Sub(rm.host.lastSeen) > 75*time.Second || (rm.guest != nil && now.Sub(rm.guest.lastSeen) > 75*time.Second)
		expired := now.Sub(rm.created) > 2*time.Hour
		if stale || expired {
			rm.host.emit("ended", nil)
			if rm.guest != nil {
				rm.guest.emit("ended", nil)
			}
			delete(s.rooms, id)
		}
	}
	for ip, e := range s.rates {
		if now.Sub(e.since) > 2*time.Minute {
			delete(s.rates, ip)
		}
	}
}
func splitEnv(key string) []string {
	v := strings.TrimSpace(os.Getenv(key))
	if v == "" {
		return nil
	}
	out := []string{}
	for _, s := range strings.Split(v, ",") {
		if s = strings.TrimSpace(s); s != "" {
			out = append(out, s)
		}
	}
	return out
}
func main() {
	s := newServer()
	s.stun = splitEnv("STUN_URLS")
	s.turn = splitEnv("TURN_URLS")
	s.turnSecret = os.Getenv("TURN_SECRET")
	if (len(s.turn) > 0) != (s.turnSecret != "") {
		log.Fatal("TURN_URLS and TURN_SECRET must be configured together")
	}
	addr := os.Getenv("LISTEN_ADDR")
	if addr == "" {
		addr = "127.0.0.1:8080"
	}
	h := &http.Server{Addr: addr, Handler: s.routes(), ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 10 * time.Second, WriteTimeout: 30 * time.Second, IdleTimeout: 60 * time.Second, MaxHeaderBytes: 16 * 1024}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	go func() {
		ticker := time.NewTicker(15 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				s.sweep()
			}
		}
	}()
	go func() {
		<-ctx.Done()
		c, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = h.Shutdown(c)
	}()
	log.Printf("signaling listening on %s; TURN configured: %t", addr, len(s.turn) > 0)
	cert, key := os.Getenv("TLS_CERT_FILE"), os.Getenv("TLS_KEY_FILE")
	if (cert == "") != (key == "") {
		log.Fatal("TLS_CERT_FILE and TLS_KEY_FILE must be configured together")
	}
	var err error
	if cert != "" {
		err = h.ListenAndServeTLS(cert, key)
	} else {
		err = h.ListenAndServe()
	}
	if err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Fatal(err)
	}
}
