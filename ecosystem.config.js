module.exports = {
  apps: [
    {
      name: "noemtaddons-server",
      script: "server/server.py",
      interpreter: "python3",
      args: "--host 0.0.0.0 --port 8765 --branch master --poll-interval 60",
      env: {
        DISCORD_WEBHOOK_URL: "https://discord.com/api/webhooks/YOUR_WEBHOOK_URL"
      },
      autorestart: true,
      restart_delay: 5000,
      max_restarts: 10,
      watch: false
    }
  ]
};
