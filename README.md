# SMS JSON Gateway for Android

A deliberately simple Android SMS worker. The app does not own queue semantics, deduplication, or delivery policy. It reads a configured JSON array, sends SMS through the phone SIM, and POSTs the send result to another configured URL.

## Input

The Source URL is called with HTTP GET and must return:

```json
[
  { "id": "sms-1001", "to": "+15551234567", "text": "Your order is ready" },
  { "id": "sms-1002", "to": "+15557654321", "text": "Table confirmed" }
]
```

The app intentionally does not persist or deduplicate IDs. If the server returns the same SMS again, it may be sent again.

## Callback

After Android reports the send attempt as successful or failed, the app POSTs to the configured Result URL:

```json
{
  "id": "sms-1001",
  "status": "OK",
  "to": "+15551234567",
  "at": "2026-08-23T10:00:00Z"
}
```

A failure uses `status: "KO"` and adds an `error` field.

## Modes

**Manual** loads and displays the full SMS list, then asks Send / Skip / Stop for each message.

**Automatic** runs a foreground service and polls every configured number of seconds, sending returned SMS sequentially.

## Notes

- Requires a physical Android phone with SMS capability and an active SIM.
- Requires runtime `SEND_SMS` permission.
- HTTP and HTTPS URLs are allowed for local/LAN deployments; prefer HTTPS on untrusted networks.
- `OK` means `SmsManager` reported the SMS as sent; it is not a carrier delivery receipt.

## APK

GitHub Actions builds `app-debug.apk` and publishes it as the `sms-json-gateway-apk` artifact.
