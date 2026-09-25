# Camera and chat protocol (0.4.0)

The existing room HTTP API is unchanged. Hosts offer one mixed audio transceiver, one screen video transceiver, one camera video transceiver, and an SCTP data transport. Both video transceivers are send/receive. Answerers reuse the offered video slots in order (screen first, camera second); all subsequent negotiation is host initiated, including ICE restarts. Camera capture is opt-in and independent of the screen-share reservation.

The host creates the ordered, reliable DataChannel `companion-v1`. The guest accepts that channel. Messages are UTF-8 JSON strings:

- `{"type":"chat","text":"Hello"}`
- `{"type":"camera","enabled":true}`

A chat message must contain a nonblank string no longer than 2,000 UTF-16 code units. Each client retains at most 200 messages in memory. Binary frames, messages over 16,384 bytes, malformed JSON, invalid field types, and unknown types are ignored. Sending is disabled when disconnected; local chat sends are rejected while the buffered amount exceeds 65,536 bytes. Camera state is sent on channel open and every camera start/stop.

Text is displayed as plain text. There are no read receipts, persisted history, server-side message storage, or offline queues. WebRTC transport encryption also applies when TURN relays the traffic. A TURN relay still consumes server bandwidth.

Screen quality controls affect only the screen sender. Camera targets are 1280×720, 30 FPS and a 2 Mbps bitrate ceiling. Negotiated codecs, hardware limits and congestion can lower actual output.

## Android ownership

The Java WebRTC SDK disposes previously returned transceiver wrappers, including their cached senders and receivers, each time `PeerConnection.getTransceivers()` runs. Read it once after applying remote SDP and refresh **all** retained track/sender references together. Do not call it from statistics callbacks or individual track callbacks.

## Upgrade

Update both participants together. Version 0.3.0 has a single video slot and does not distinguish camera video from screen video. No interoperability guarantee is made for mixed versions.
