# WiSL Vault

Armazenamento local de arquivos para testes de upload em smartphones na rede Wi-Fi. Funciona como um drive pessoal sem autenticação — o computador roda o servidor e os dispositivos na mesma rede acessam por `http://wislvault.local`.

Inclui um **app Android nativo** (WiSL Vault) e uma **interface web** responsiva.

## Funcionalidades

### App Android (WiSL Vault)
- Conexão ao servidor pelo endereço `http://wislvault.local/api`
- Upload de arquivos via seletor do sistema
- Listagem de arquivos com nome, tamanho e data de upload
- Menu de opções por arquivo: download, informações, exclusão
- Seleção múltipla com long-press: download ou exclusão em lote
- Download com notificação em tempo real (nome, progresso, velocidade e ETA)
- Interface em inglês ou pt-BR conforme o idioma do dispositivo

### Interface Web
- Upload via toque (mobile) ou drag & drop (desktop)
- Seleção múltipla de arquivos com envio sequencial
- Barra de progresso em tempo real com percentual
- Listagem com nome, tamanho e data
- Download e exclusão de arquivos
- Interface responsiva, otimizada para smartphones

## Arquitetura

```
WiSL_Vault/
├── install.sh                    # Instalação (executar uma vez)
├── docker-compose.yml
├── uploads/                      # Arquivos armazenados (criado automaticamente)
├── scripts/                      # Scripts de ciclo de vida do servidor
│   ├── start.sh
│   ├── stop.sh
│   ├── wisl-vault.service        # Unit systemd (template)
│   ├── 99-wisl-vault             # NetworkManager dispatcher
│   └── wisl-vault.sudoers
├── backend/                      # API FastAPI
│   ├── Dockerfile
│   ├── pyproject.toml
│   └── main.py
├── frontend/                     # Interface React (servida via nginx)
│   ├── Dockerfile
│   ├── nginx.conf
│   ├── vite.config.js
│   └── src/
│       ├── App.jsx
│       └── index.css
└── android/                      # App Android nativo (Kotlin)
```

| Camada   | Tecnologia         | Papel                                              |
|----------|--------------------|----------------------------------------------------|
| Frontend | React + Vite       | Interface web de usuário                           |
| Proxy    | nginx              | Serve o React e repassa `/api/*` para o backend    |
| Backend  | FastAPI            | Upload, listagem, download e exclusão de arquivos  |
| Storage  | Volume Docker      | Pasta `./uploads` persistida no host               |
| mDNS     | avahi-daemon       | Anuncia `wislvault.local` na rede local            |
| Android  | Kotlin + Material3 | App nativo para Android 15+                        |

O smartphone acessa a porta 80 pelo nome `wislvault.local`. O nginx serve o frontend e encaminha `/api/*` internamente para o FastAPI — sem IP hardcoded no código.

## Pré-requisitos

### Servidor
- Linux com [Docker](https://docs.docker.com/get-docker/) e NetworkManager
- Usuário no grupo `sudo`

### App Android
- Android Studio (para compilar)
- Android 15+ (minSdk 35)

## Instalação (uma vez por máquina)

```bash
git clone <repo>
cd WiSL_Vault
chmod +x install.sh
./install.sh
```

O que `install.sh` faz:
1. Instala regras sudoers para operação sem senha interativa
2. Instala dispatcher do NetworkManager (reage a trocas de rede)
3. Instala e habilita o serviço systemd `wisl-vault`

Após a instalação, o servidor **sobe automaticamente no boot**.

## Como executar o servidor

### Iniciar / parar

```bash
sudo systemctl start wisl-vault    # inicia
sudo systemctl stop wisl-vault     # para
sudo systemctl status wisl-vault   # verifica status
journalctl -u wisl-vault -f        # acompanha logs
```

### Acesso

Todos os dispositivos na mesma rede Wi-Fi acessam por:

```
http://wislvault.local
```

No app Android, use `http://wislvault.local/api` como endereço do servidor.

### O que acontece ao iniciar

1. Verifica se outro host na rede já serve `wislvault.local` — se sim, encerra sem subir nada
2. Configura e inicia o `avahi-daemon` para anunciar `wislvault.local` via mDNS
3. Confirma que este host ganhou o anúncio (cobre boot simultâneo de múltiplos hosts)
4. Gera arquivos de teste em `uploads/` (500 MB a 20 GB, ~4 min)
5. Sobe os containers via Docker Compose

### Múltiplos hosts na rede

Apenas um host serve `wislvault.local` por vez. Se outro host já tem o domínio, o servidor neste host **não sobe**. Ao trocar de rede, o servidor reinicia automaticamente e refaz a verificação.

### Sem Docker (desenvolvimento)

**Backend:**
```bash
cd backend
uv sync
uv run fastapi dev main.py --host 0.0.0.0   # hot-reload em :8000
```

**Frontend:**
```bash
cd frontend
npm install
npm run dev   # Vite em :5173
```

O `vite.config.js` faz proxy de `/api` para `http://localhost:8000` e expõe o servidor na rede local (`host: '0.0.0.0'`). O terminal imprime a URL de rede para compartilhar com o smartphone.

## App Android

O app está no diretório `android/`. Abra-o no Android Studio, conecte um dispositivo com Android 15 ou superior e execute via Run.

Na tela inicial, informe o endereço do servidor `http://wislvault.local/api` e toque em **Connect**.

**Permissões solicitadas:**
- `INTERNET` — comunicação com o servidor
- `POST_NOTIFICATIONS` — notificações de progresso de download

## Endpoints da API

| Método   | Rota                  | Descrição                  |
|----------|-----------------------|----------------------------|
| `GET`    | `/files`              | Lista todos os arquivos     |
| `POST`   | `/upload`             | Envia um arquivo            |
| `GET`    | `/files/{filename}`   | Faz download de um arquivo  |
| `DELETE` | `/files/{filename}`   | Exclui um arquivo           |
