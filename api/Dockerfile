# The API in one container. Hosts that set PORT (Heroku, Railway, Render) get it; otherwise 8000.
FROM python:3.12-slim
COPY --from=ghcr.io/astral-sh/uv:0.11 /uv /bin/uv
WORKDIR /app
ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy UV_NO_DEV=1 PATH="/app/.venv/bin:$PATH" DATABASE_PATH=/data/events.db
COPY pyproject.toml uv.lock .python-version ./
RUN uv sync --locked --no-dev --no-install-project
COPY app ./app
VOLUME /data
EXPOSE 8000
CMD ["sh", "-c", "exec uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8000} --proxy-headers --forwarded-allow-ips='*'"]
