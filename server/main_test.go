package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http/httptest"
	"testing"
	"time"
)

func request(s *server, method, path, token, body string) *httptest.ResponseRecorder {
	r := httptest.NewRequest(method, path, bytes.NewBufferString(body))
	r.Header.Set("Authorization", "Bearer "+token)
	w := httptest.NewRecorder()
	s.routes().ServeHTTP(w, r)
	return w
}
func setupRoom(t *testing.T, s *server) (string, string, string) {
	t.Helper()
	w := request(s, "POST", "/v1/rooms", "", "")
	if w.Code != 201 {
		t.Fatal(w.Body.String())
	}
	var a, b map[string]any
	_ = json.Unmarshal(w.Body.Bytes(), &a)
	id := a["roomId"].(string)
	w = request(s, "POST", "/v1/rooms/"+id+"/join", "", "")
	if w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	_ = json.Unmarshal(w.Body.Bytes(), &b)
	return id, a["token"].(string), b["token"].(string)
}
func TestRoomAndSignaling(t *testing.T) {
	s := newServer()
	id, host, guest := setupRoom(t, s)
	path := "/v1/rooms/" + id
	for _, tc := range []struct {
		method, path, token, body string
		status                    int
	}{
		{"POST", path + "/join", "", "", 409},
		{"GET", path + "/events?after=0", "wrong", "", 401},
		{"POST", path + "/signal", guest, `{"type":"offer","data":{}}`, 403},
		{"POST", path + "/signal", host, `{"type":"offer","data":{"sdp":"example"}}`, 200},
		{"POST", path + "/signal", guest, `{"type":"answer","data":{"sdp":"answer"}}`, 200},
		{"POST", path + "/signal", host, `{"type":"ice","data":{"candidate":"example"}}`, 200},
		{"POST", path + "/signal", host, `{"type":"unknown"}`, 400},
	} {
		w := request(s, tc.method, tc.path, tc.token, tc.body)
		if w.Code != tc.status {
			t.Fatalf("%s got %d: %s", tc.path, w.Code, w.Body.String())
		}
	}
	w := request(s, "GET", path+"/events?after=0", guest, "")
	var result struct {
		Events []event `json:"events"`
	}
	_ = json.Unmarshal(w.Body.Bytes(), &result)
	if len(result.Events) != 2 || result.Events[0].Type != "offer" || result.Events[1].Type != "ice" {
		t.Fatal(w.Body.String())
	}
	// Poll replay is idempotent, preventing lost SDP/ICE on a failed HTTP response.
	replay := request(s, "GET", path+"/events?after=0", guest, "")
	if replay.Body.String() != w.Body.String() {
		t.Fatal("replay changed")
	}
}
func TestSingleSharer(t *testing.T) {
	s := newServer()
	id, h, g := setupRoom(t, s)
	path := "/v1/rooms/" + id + "/share"
	for _, tc := range []struct {
		token   string
		enabled bool
		want    int
	}{{h, true, 200}, {g, true, 409}, {g, false, 200}, {g, true, 409}, {h, false, 200}, {g, true, 200}} {
		w := request(s, "POST", path, tc.token, fmt.Sprintf(`{"enabled":%t}`, tc.enabled))
		if w.Code != tc.want {
			t.Fatal(w.Code, w.Body.String())
		}
	}
}
func TestExpiryAndRateLimit(t *testing.T) {
	s := newServer()
	now := time.Now()
	s.now = func() time.Time { return now }
	id, _, _ := setupRoom(t, s)
	now = now.Add(76 * time.Second)
	s.sweep()
	if s.rooms[id] != nil {
		t.Fatal("stale room retained")
	}
	for i := 0; i < 30; i++ {
		if w := request(s, "POST", "/v1/rooms", "", ""); w.Code != 201 {
			t.Fatal(w.Code)
		}
	}
	if w := request(s, "POST", "/v1/rooms", "", ""); w.Code != 429 {
		t.Fatal("missing rate limit")
	}
}
func TestEndedEvent(t *testing.T) {
	s := newServer()
	id, h, g := setupRoom(t, s)
	path := "/v1/rooms/" + id
	request(s, "DELETE", path, h, "")
	w := request(s, "GET", path+"/events?after=0", g, "")
	if !bytes.Contains(w.Body.Bytes(), []byte(`"ended"`)) {
		t.Fatal(w.Body.String())
	}
}
func TestTurnCredential(t *testing.T) {
	s := newServer()
	s.turn = []string{"turn:example.org:3478"}
	s.turnSecret = "test"
	c := s.credentials("host")
	if len(c) != 1 || c[0].Username == "" || c[0].Credential == "" {
		t.Fatal(c)
	}
}
func TestEventWindow(t *testing.T) {
	s := newServer()
	id, _, g := setupRoom(t, s)
	p := s.rooms[id].guest
	for i := 0; i < 300; i++ {
		p.emit("ice", nil)
	}
	w := request(s, "GET", "/v1/rooms/"+id+"/events?after=0", g, "")
	if w.Code != 410 {
		t.Fatal(w.Code)
	}
}

func TestRetryDoesNotDuplicateOffer(t *testing.T) {
	s := newServer()
	id, h, g := setupRoom(t, s)
	path := "/v1/rooms/" + id
	for i := 0; i < 2; i++ {
		w := request(s, "POST", path+"/signal", h, `{"id":"retry-1","type":"offer","data":{"sdp":"example"}}`)
		if w.Code != 200 {
			t.Fatal(w.Body.String())
		}
	}
	w := request(s, "GET", path+"/events?after=0", g, "")
	var result struct {
		Events []event `json:"events"`
	}
	_ = json.Unmarshal(w.Body.Bytes(), &result)
	if len(result.Events) != 1 {
		t.Fatal("retry duplicated", w.Body.String())
	}
	request(s, "DELETE", path, h, "")
	if w := request(s, "POST", path+"/share", g, `{"enabled":true}`); w.Code != 410 {
		t.Fatal("sharing after end", w.Code)
	}
}

func TestLongPollWakesOnJoin(t *testing.T) {
	s := newServer()
	w := request(s, "POST", "/v1/rooms", "", "")
	var a map[string]any
	_ = json.Unmarshal(w.Body.Bytes(), &a)
	id, token := a["roomId"].(string), a["token"].(string)
	done := make(chan *httptest.ResponseRecorder, 1)
	go func() { done <- request(s, "GET", "/v1/rooms/"+id+"/events?after=0", token, "") }()
	request(s, "POST", "/v1/rooms/"+id+"/join", "", "")
	select {
	case response := <-done:
		if response.Code != 200 || !bytes.Contains(response.Body.Bytes(), []byte("peer-joined")) {
			t.Fatal(response.Body.String())
		}
	case <-time.After(2 * time.Second):
		t.Fatal("long poll was not notified")
	}
}
