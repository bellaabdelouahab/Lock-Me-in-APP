# Your own DNS server: setup

Blocky runs next to the relay (`server/docker-compose.yml`, rules in `server/dns/config.yml`, FEATURES 3.17). The phone reaches
it as `dns.abdelouahab.xyz` over DNS-over-TLS (port 853). Coolify's proxy holds the certificate.

## 1. Once, on the VPS

1. **DNS record:** `dns.abdelouahab.xyz` A record to this VPS, like the other subdomains.
2. **Oracle firewall:** in the Oracle Cloud console, the VCN's security list, add an ingress rule: TCP, source `0.0.0.0/0`,
   destination port 853.
3. **The VPS's own firewall** (it rejects everything not listed). Run these two commands:
   ```bash
   sudo iptables -I INPUT 5 -p tcp --dport 853 -j ACCEPT
   ```
   ```bash
   sudo netfilter-persistent save
   ```
4. **Coolify proxy:** Servers, localhost, Proxy, Configuration. In the Traefik `command:` list add
   `- '--entrypoints.dot.address=:853'`, and under `ports:` add `- '853:853'`. Save, then Restart Proxy.
   (Done 2026-11-25 by editing `/data/coolify/proxy/docker-compose.yml` and recreating the proxy. A proxy recreated by hand
   loses the service networks Coolify attaches to it, and every site answers 504: reconnect each with
   `sudo docker network connect <network> coolify-proxy`.) Labels use the literal host: Coolify escapes `${...}` in labels,
   so a variable there reaches Traefik as text and the DoT router never matches.

## 2. Deploy

Redeploy the Lock Me In resource (the same compose as the relay). The `dns` service starts, downloads its lists (about
350,000 domains) within a minute, and Coolify's proxy gets the certificate for `dns.abdelouahab.xyz`.

Check from the laptop: `kdig -d @dns.abdelouahab.xyz +tls example.com`, or put the name in the Android Private DNS field of
another phone.

## 3. Switch the phone

Lock Me In, Settings, Private DNS host: `dns.abdelouahab.xyz`. The app checks the server answers before saving. A change
of host is a settings change, so with protection on it waits for the Sunday window like any other.

## How the fallback behaves

If the server stops answering while the network works (3 failed checks across at least a minute), the phone uses plain
automatic DNS, unfiltered, until two checks a minute apart succeed again. The health page says so, the event log records
when and for how long, and the weekly report counts it. Offline or on a captive portal nothing changes.

## Changing the rules

Edit `server/dns/config.yml`, commit, redeploy. There is no admin page by design. Lists refresh every 12 hours.

## What the MCP reads

`dns_summary(days)`: per day, lookups, blocked, blocked by list, adult blocks by hour. `dns_log(day, blocked_only)`: the
lookups themselves. Kept 7 days on the VPS, nowhere else.
