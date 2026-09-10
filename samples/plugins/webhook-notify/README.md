# Webhook Notify

Posts JSON to `WEBHOOK_URL` when an Andy chat reaches `done` or `blocked`.

## Link

```sh
andy plugin link samples/plugins/webhook-notify
andy plugin config-dir examples.webhook-notify
```

Create `.env` in that config dir:

```env
WEBHOOK_URL=https://example.com/hooks/andy
```

## Try it

```sh
andy plugin action invoke test --plugin examples.webhook-notify
andy plugin pane open --plugin examples.webhook-notify --entrypoint log
```
