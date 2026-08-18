# LiteLLM gateway

KubePilot speaks the OpenAI HTTP dialect and nothing else. LiteLLM sits in front and translates
that one dialect into whichever provider you actually use — Ollama, OpenAI, Anthropic, Bedrock,
Vertex, Azure, Groq and so on.

Two consequences worth understanding:

- **KubePilot never knows which vendor answered.** It asks for the alias `kubepilot-fast`. Which
  model that maps to is decided here.
- **Adding a provider needs no rebuild and no application change.** Edit `config.yaml`, restart
  LiteLLM.

## Running it locally

```shell
cp .env.example .env          # fill in keys only if using a hosted provider
docker compose up -d litellm
```

To also run a local model in Docker rather than installing Ollama on the host:

```shell
docker compose --profile local-llm up -d
docker compose exec ollama ollama pull qwen3:8b
```

Check it is serving:

```shell
curl.exe -H "Authorization: Bearer sk-kubepilot-local" http://localhost:4000/v1/models
```

## Pointing KubePilot at it

Defaults in `application.yml` already target this setup, so normally nothing is needed. To
override:

| Variable | Default | Meaning |
| --- | --- | --- |
| `KUBEPILOT_LLM_URL` | `http://localhost:4000/v1` | gateway address |
| `KUBEPILOT_LLM_KEY` | `sk-kubepilot-local` | must equal `LITELLM_MASTER_KEY` |
| `KUBEPILOT_LLM_MODEL` | `kubepilot-fast` | which alias to use |

To skip LiteLLM entirely and talk to a provider directly, point `KUBEPILOT_LLM_URL` at it —
`http://localhost:11434/v1` for host-installed Ollama, or `https://api.openai.com/v1`. The
gateway is a convenience, not a requirement.

## Adding a model

Add an entry to `model_list` in `config.yaml`, put any key in `.env`, then
`docker compose restart litellm`.

```yaml
  - model_name: kubepilot-deep
    litellm_params:
      model: anthropic/claude-sonnet-5
      api_key: os.environ/ANTHROPIC_API_KEY
```

**Name aliases after the role, not the vendor.** `kubepilot-fast` and `kubepilot-deep` are good;
`gpt-5` is not, because it leaks a vendor choice into an application that should not care and has
to be changed in two places when you switch.

Keys belong in `.env` and are referenced as `os.environ/NAME`. Never write a key into
`config.yaml` — that file is committed.

## In-cluster

The same `config.yaml` becomes a ConfigMap and the same variables become a Secret, with LiteLLM
running as a sidecar so that port 4000 is reachable only by KubePilot rather than by everything in
the namespace. That arrives with the Helm chart at M3.
