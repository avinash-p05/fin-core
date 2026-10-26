# Deploying FinCore to a VPS (hands-on guide)

Target: one Ubuntu 24.04 VPS (Contabo Cloud VPS 4: 4 vCPU, 8 GB RAM), Docker Compose, Caddy for HTTPS.
Replace `YOUR_IP` everywhere with your server's IPv4 address.

Commands marked **[laptop]** run in PowerShell on your Windows machine; **[server]** run inside the SSH session.

---

## 0. Concepts in one minute

- **SSH** gives you a remote shell on the server. You authenticate with a **key pair**: the private key
  (`~/.ssh/contabo_vps`) never leaves your laptop; the public key (`contabo_vps.pub`) goes on the server.
- **root** can do anything. You'll create a normal user (`deploy`) for daily work and use `sudo` when needed.
- **UFW** is the firewall. Only ports 22 (SSH), 80 and 443 (web) will be open.
- **Docker Compose** runs FinCore's six containers (postgres, redis, kafka, backend, frontend, caddy) from
  `infrastructure/docker-compose.prod.yml`. Only Caddy publishes ports; everything else is internal.
- **Caddy** gets a free Let's Encrypt certificate automatically. With no domain yet we use
  `fincore.YOUR_IP.sslip.io`, a free DNS name that resolves to your IP.

---

## 1. Push the production files to GitHub [laptop]

The prod compose file, Caddyfile and `.env.prod.example` exist only on your laptop. Commit and push them
so the server can `git clone` the repo:

```powershell
cd D:\FinCore
git add infrastructure/docker-compose.prod.yml infrastructure/Caddyfile infrastructure/.env.prod.example .gitignore docs/deploy-vps.md
git commit -m "Add production compose, Caddy HTTPS and VPS deploy guide"
git push
```

`infrastructure/.env.prod` (real passwords) is in `.gitignore` and must never be committed.

---

## 2. First login and SSH key [laptop]

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

Test: `ssh vps` should log you in **without** a password. (After step 4 you'll change `User root` to `User deploy`.)

---

## 3. Update the system [server]

```bash
apt update && apt upgrade -y
timedatectl set-timezone Asia/Kolkata
apt install -y git htop ufw unattended-upgrades fail2ban
dpkg-reconfigure -f noninteractive unattended-upgrades   # automatic security updates
```

If it says a reboot is required: `reboot`, wait a minute, `ssh vps` again.

---

## 4. Create a non-root user [server]

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

## 5. Lock down SSH [server]

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

## 6. Firewall [server]

```bash
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw enable          # answer y
sudo ufw status verbose
```

Note: Docker writes its own iptables rules and bypasses UFW for *published* ports. That's why the prod
compose file publishes only Caddy's 80/443; Postgres, Redis and Kafka have no `ports:` at all.

---

## 7. Add 2 GB swap (safety net for builds) [server]

```bash
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile
sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
free -h
```

---

## 8. Install Docker [server]

```bash
curl -fsSL https://get.docker.com -o get-docker.sh
less get-docker.sh                # skim it: it's Docker's official installer (q to quit)
sudo sh get-docker.sh
sudo usermod -aG docker deploy    # run docker without sudo
exit                              # log out and back in for the group to apply
```

`ssh vps` again, then check: `docker run --rm hello-world` and `docker compose version`.

---

## 9. Get the code and set secrets [server]

```bash
cd ~
git clone https://github.com/avinash-p05/fin-core.git
cd fin-core
cp infrastructure/.env.prod.example infrastructure/.env.prod
nano infrastructure/.env.prod
```

Fill it in (generate each password with `openssl rand -base64 18`):

```
FINCORE_DOMAIN=fincore.YOUR_IP.sslip.io
FINCORE_DB_PASSWORD=<random>
FINCORE_OPERATOR_PASSWORD=<a demo password you're happy to share with recruiters>
FINCORE_ANALYST_PASSWORD=<random, keep private>
```

Save (Ctrl+O, Enter, Ctrl+X), then `chmod 600 infrastructure/.env.prod`.

---

## 10. Build and start [server]

```bash
docker compose -f infrastructure/docker-compose.prod.yml --env-file infrastructure/.env.prod up -d --build
```

The first build downloads Maven dependencies and npm packages: expect 5–10 minutes. Watch progress:

```bash
docker compose -f infrastructure/docker-compose.prod.yml --env-file infrastructure/.env.prod ps
docker compose -f infrastructure/docker-compose.prod.yml --env-file infrastructure/.env.prod logs -f backend   # Ctrl+C to stop following
```

Tip: avoid retyping the long flags with an alias in `~/.bashrc`:

```bash
echo "alias fc='docker compose -f ~/fin-core/infrastructure/docker-compose.prod.yml --env-file ~/fin-core/infrastructure/.env.prod'" >> ~/.bashrc
source ~/.bashrc
fc ps
```

All services should show `running` / `healthy` (backend takes ~1 minute after Kafka is healthy).

---

## 11. Verify [laptop + server]

- **[server]** `fc logs caddy | grep -i certificate` should show the certificate was obtained.
- **[server]** `fc exec backend wget -qO- http://localhost:8080/actuator/health` returns `{"status":"UP"...}`.
- **[laptop]** open `https://fincore.YOUR_IP.sslip.io` → padlock in the browser → log in as
  `operator` / your operator password → send a payment from New Payment and check the Ledger page.
- **[server]** `docker stats --no-stream` shows memory per container (FinCore should total about 2–3 GB).

---

## 12. Day-to-day operations [server]

| Task | Command |
|---|---|
| Status | `fc ps` |
| Logs (last 100 lines, follow) | `fc logs -f --tail=100 backend` |
| Restart one service | `fc restart backend` |
| Deploy new code | `cd ~/fin-core && git pull && fc up -d --build` |
| Stop everything | `fc down` (data kept in volumes) |
| Wipe data and reseed | `fc down -v` then `fc up -d` (**deletes the database**) |
| DB backup | `fc exec -T postgres pg_dump -U fincore fincore > ~/fincore-$(date +%F).sql` |
| Free disk from old images | `docker image prune -f` |
| Resource usage | `htop`, `docker stats`, `df -h` |

---

## 13. Troubleshooting

- **Browser can't connect / no certificate:** `sudo ufw status` must allow 80 and 443; `fc logs caddy`.
  Let's Encrypt needs port 80 reachable. Check Contabo's panel firewall isn't blocking it.
- **Backend keeps restarting:** `fc logs backend`. Usually Postgres or Kafka not healthy yet, or a typo
  in `.env.prod`.
- **Container killed (exit 137):** out of memory. `docker stats`, then raise that service's `mem_limit`.
- **Locked out of SSH:** use the VNC/console access in the Contabo customer panel to log in and fix
  `/etc/ssh/sshd_config.d/00-hardening.conf`.

---

## 14. Later: your own domain

Buy a domain, add an **A record** `fincore` → `YOUR_IP`, set `FINCORE_DOMAIN=fincore.yourdomain.dev` in
`.env.prod`, then `fc up -d`. Caddy fetches the new certificate automatically.
