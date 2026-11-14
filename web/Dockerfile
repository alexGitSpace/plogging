# The web app as static files behind Caddy, for Railway or any host that sets PORT.
# API_URL (an environment variable, not in git) becomes js/config.js when the container starts.
FROM caddy:2.8-alpine
COPY . /srv/
RUN mv /srv/Caddyfile /etc/caddy/Caddyfile
CMD ["sh", "-c", "if [ -n \"$API_URL\" ]; then printf 'window.PLOGGING_CONFIG = { apiUrl: \"%s\" };\\n' \"$API_URL\" > /srv/js/config.js; fi; exec caddy run --config /etc/caddy/Caddyfile --adapter caddyfile"]
