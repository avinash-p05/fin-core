# Deploying FinCore to a VPS (hands-on guide)

Target: one Ubuntu 24.04 VPS (Contabo Cloud VPS 4: 4 vCPU, 8 GB RAM) running Docker Compose, with the
shared **edge** (Caddy, HTTPS) and **platform** (PostgreSQL, Redis, Kafka) stacks that every app uses.
Replace `YOUR_IP` everywhere with your server's IPv4 address.

Commands marked **[laptop]** run in PowerShell on your Windows machine; **[server]** run inside the SSH session.

---

## 0. Concepts in one minute

- **SSH** gives you a remote shell on the server. You authenticate with a **key pair**: the private key
  (`~/.ssh/contabo_vps`) never leaves your laptop; the public key (`contabo_vps.pub`) goes on the server.
- **root** can do anything. You'll create a normal user (`deploy`) for daily work and use `sudo` when needed.
- **UFW** is the firewall. Only ports 22 (SSH), 80 and 443 (web) will be open.
- The server has three kinds of Docker Compose projects:

  | Folder | What it runs | Shared by |
  |---|---|---|
  | `~/edge` | Caddy on 80/443: HTTPS certificates, routes each domain to its app | all apps |
  | `~/platform` | `platform-postgres`, `platform-redis`, `platform-kafka` | all apps |
  | `~/fincore` | **one container**, `fincore-app:8080` (dashboard + API), pulled from ghcr.io | FinCore only |

- FinCore lives on its own private Docker network, `app-fincore`. Only the edge and the platform
  services join it, so other apps can't reach FinCore and FinCore can't reach them. FinCore publishes
  port 8080 on `127.0.0.1` only, so it's never reachable from the internet directly.
- With no domain yet, use `fincore.YOUR_IP.sslip.io` (a free DNS name that resolves to your IP) or a
  free DuckDNS name.

---

## 1. First login and SSH key [laptop]

Copy your public key to the server (asks for the root password from Contabo's email, once):

```powershell
type $env:USERPROFILE\.ssh\contabo_vps.pub | ssh root@YOUR_IP "mkdir -p ~/.ssh && chmod 700 ~/.ssh && cat >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys"
```

Answer `yes` to the fingerprint question. Then add a shortcut so you can type `ssh vps`. Open
`C:\Users\Asus\.ssh\config` in a text editor and append:

```
Host vps
    HostName YOUR_IP
    User root
    IdentityFile ~/.ssh/contabo_vps
```

Test: `ssh vps` should log you in **without** a password. (After step 3 you'll change `User root` to `User deploy`.)

---

## 2. Update the system [server]

```bash
apt update && apt upgrade -y
timedatectl set-timezone Asia/Kolkata
apt install -y git htop ufw unattended-upgrades fail2ban
dpkg-reconfigure -f noninteractive unattended-upgrades   # automatic security updates
```

If it says a reboot is required: `reboot`, wait a minute, `ssh vps` again.

---

## 3. Create a non-root user [server]

```bash
adduser deploy                       # choose a password (used for sudo), other fields can be blank
usermod -aG sudo deploy
mkdir -p /home/deploy/.ssh
cp ~/.ssh/authorized_keys /home/deploy/.ssh/
chown -R deploy:deploy /home/deploy/.ssh
chmod 700 /home/deploy/.ssh && chmod 600 /home/deploy/.ssh/authorized_keys
```

**[laptop]** open a *second* terminal and check `ssh -i ~/.ssh/contabo_vps deploy@YOUR_IP` works and
`sudo whoami` prints `root`. Then change `User root` to `User deploy` in your SSH config.

---

## 4. Lock down SSH [server]

Keep your first session open until you've tested a new login (so a mistake can't lock you out).

```bash
sudo tee /etc/ssh/sshd_config.d/00-hardening.conf > /dev/null <<'EOF'
PermitRootLogin no
PasswordAuthentication no
KbdInteractiveAuthentication no
EOF
sudo sshd -t && sudo systemctl restart ssh
```

Why `00-`: sshd uses the *first* value it reads, and Ubuntu's cloud image ships `50-cloud-init.conf`
which may enable passwords; `00-` is read first.

Test from a new laptop terminal: `ssh vps` works; `ssh root@YOUR_IP` is refused.

---

## 5. Firewall [server]

```bash
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw enable          # answer y
sudo ufw status verbose
```

Note: Docker writes its own iptables rules and bypasses UFW for *published* ports. That's why only the
edge publishes 80/443, the platform services publish nothing, and FinCore publishes 8080 on
`127.0.0.1` only.

---

## 6. Add 2 GB swap (safety net for builds) [server]

```bash
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile
sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
free -h
```

---

## 7. Install Docker [server]

```bash
curl -fsSL https://get.docker.com -o get-docker.sh
less get-docker.sh                # skim it: it's Docker's official installer (q to quit)
sudo sh get-docker.sh
sudo usermod -aG docker deploy    # run docker without sudo
exit                              # log out and back in for the group to apply
```

`ssh vps` again, then check: `docker run --rm hello-world` and `docker compose version`.

---

## 8. Edge and platform [server]

These are set up once per server and shared by every app; see their own READMEs (`~/edge/README.md`,
`~/platform/README.md`). FinCore needs:

```bash
docker network create app-fincore     # once; then add app-fincore to ~/edge/compose.yaml and
                                      # ~/platform/compose.yaml (postgres, redis, kafka) and restart them
```

Check: `docker network inspect app-fincore --format '{{range .Containers}}{{.Name}} {{end}}'` lists
`edge-caddy platform-postgres platform-redis platform-kafka`.

---

## 9. How code reaches the server

The server never builds and holds no source code. GitHub Actions does the work
([.github/workflows/ci.yml](../.github/workflows/ci.yml)):

```
git push main ─► GitHub Actions: tests (Testcontainers) ─► docker build ─► ghcr.io/avinash-p05/fin-core:latest
                                                                          ghcr.io/avinash-p05/fin-core:<commit-sha>
server ~/fincore:  compose.yaml + .env + deploy.sh  ─►  ./deploy.sh  (pull the image, restart, wait for healthy)
```

`deploy.sh` also refreshes `compose.yaml` and `fincore.caddy` from `main`, so the server's copies
can't drift from the repo.

---

## 10. First deploy [server]

```bash
mkdir -p ~/fincore && cd ~/fincore
raw=https://raw.githubusercontent.com/avinash-p05/fin-core/main
curl -fsSLO $raw/compose.yaml
curl -fsSL $raw/deploy/deploy.sh -o deploy.sh && chmod +x deploy.sh
curl -fsSL $raw/.env.example -o .env && chmod 600 .env
nano .env
```

Fill it in (generate each password with `openssl rand -base64 18`):

```
FINCORE_DB_PASSWORD=<random>
FINCORE_OPERATOR_PASSWORD=<a demo password you're happy to share with recruiters>
FINCORE_ANALYST_PASSWORD=<random, keep private>
FINCORE_ALLOWED_ORIGINS=https://fin-core.duckdns.org
```

Create FinCore's database and role on the shared Postgres (idempotent; re-running resets the password),
start FinCore, then route the domain to it:

```bash
APP_DB_PASSWORD="$(grep ^FINCORE_DB_PASSWORD= .env | cut -d= -f2-)" ~/platform/create-app-db.sh fincore
./deploy.sh
cp fincore.caddy ~/edge/sites/ && ~/edge/reload.sh
```

Flyway creates the tables and the app creates its Kafka topics on first start.

**Every later deploy:** push to `main`, wait for the green CI run, then `cd ~/fincore && ./deploy.sh`.
**Roll back:** set `FINCORE_IMAGE_TAG=<older commit sha>` in `.env` and run `./deploy.sh`.

---

## 11. Verify [laptop + server]

- **[server]** `curl -s http://127.0.0.1:8080/actuator/health` returns `{"status":"UP"...}`.
- **[server]** `docker logs edge-caddy 2>&1 | grep -i certificate` shows the certificate was obtained.
- **[laptop]** open `https://fin-core.duckdns.org` → padlock in the browser → log in as
  `operator` / your operator password → send a payment from New Payment and check the Ledger page.
- **[server]** `docker stats --no-stream` shows memory per container (FinCore itself is about 1 GB).

---

## 12. Day-to-day operations [server]

| Task | Command (in `~/fincore`) |
|---|---|
| Status | `docker compose ps` |
| Logs (last 100 lines, follow) | `docker compose logs -f --tail=100 app` |
| Restart | `docker compose restart app` |
| Deploy new code | push to `main`, wait for CI, then `./deploy.sh` |
| Roll back | `FINCORE_IMAGE_TAG=<sha>` in `.env`, then `./deploy.sh` |
| Stop FinCore | `docker compose down` (data stays in the platform's Postgres) |
| Wipe data and reseed | `docker exec platform-postgres psql -U postgres -c 'DROP DATABASE fincore'`, re-run `create-app-db.sh fincore`, then `docker compose restart app` (**deletes FinCore's data**) |
| DB backup | Done for all apps by `~/platform/backup.sh` (daily, plus off-site). One-off: `docker exec platform-postgres pg_dump -U postgres fincore > ~/fincore-$(date +%F).sql` |
| Free disk from old images | `docker image prune -f` |
| Resource usage | `htop`, `docker stats`, `df -h` |

---

## 13. Troubleshooting

- **Browser can't connect / no certificate:** `sudo ufw status` must allow 80 and 443; `docker logs edge-caddy`.
  Let's Encrypt needs port 80 reachable. Check Contabo's panel firewall isn't blocking it.
- **502 from the edge:** FinCore isn't up or isn't on `app-fincore`. `docker compose ps`, and check
  `edge-caddy` is listed by `docker network inspect app-fincore`.
- **App keeps restarting:** `docker compose logs app`. Usually a wrong `FINCORE_DB_PASSWORD` (must match
  `create-app-db.sh`), or a platform service not on `app-fincore`.
- **Container killed (exit 137):** out of memory. `docker stats`, then raise `mem_limit` in `compose.yaml`.
- **Locked out of SSH:** use the VNC/console access in the Contabo customer panel to log in and fix
  `/etc/ssh/sshd_config.d/00-hardening.conf`.

---

## 14. Later: your own domain

Buy a domain, add an **A record** `fincore` → `YOUR_IP`, change the domain in
`deploy/edge/fincore.caddy` in the repo and push, set `FINCORE_ALLOWED_ORIGINS` in `.env`, then
`./deploy.sh && cp fincore.caddy ~/edge/sites/ && ~/edge/reload.sh`. The edge
fetches the new certificate automatically.
