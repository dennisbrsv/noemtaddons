module.exports = {
  apps: [
    {
      name: "noemtaddons-control-plane",
      script: "server/main.py",
      interpreter: "python3",
      args: "--host 0.0.0.0 --port 8765",
      env: {
        DISCORD_BOT_TOKEN: "YOUR_DISCORD_BOT_TOKEN_HERE",
        DISCORD_CHANNEL_ID: "YOUR_DISCORD_CHANNEL_ID_HERE",
        AUTH_SECRET: ""
      },
      autorestart: true,
      restart_delay: 5000,
      max_restarts: 10,
      watch: false
    }
  ]
};
