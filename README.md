# WiSL Vault

Armazenamento local de arquivos para testes de upload em smartphones na rede Wi-Fi. Funciona como um drive pessoal sem autenticação — o computador roda o servidor e os dispositivos na mesma rede acessam por IP.

Inclui um **app Android nativo** (WiSL Vault) e uma **interface web** responsiva.

## Funcionalidades

### App Android (WiSL Vault)
- Conexão ao servidor pelo IP local
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
wisl_upload/
├── docker-compose.yml
├── uploads/                  # Arquivos armazenados (criado automaticamente)
├── backend/                  # API FastAPI
│   ├── Dockerfile
│   ├── pyproject.toml
│   └── main.py
├── frontend/                 # Interface React (servida via nginx)
│   ├── Dockerfile
│   ├── nginx.conf
│   ├── vite.config.js
│   └── src/
│       ├── App.jsx
│       └── index.css
└── android/                  # App Android nativo (Kotlin)
    └── app/src/main/
        ├── AndroidManifest.xml
        └── java/com/example/wislvault/
            ├── MainActivity.kt
            ├── ConectionActivity.kt   # tela de conexão
            └── VaultActivity.kt       # listagem e upload
```

| Camada   | Tecnologia       | Papel                                              |
|----------|------------------|----------------------------------------------------|
| Frontend | React + Vite     | Interface web de usuário                           |
| Proxy    | nginx            | Serve o React e repassa `/api/*` para o backend    |
| Backend  | FastAPI          | Upload, listagem, download e exclusão de arquivos  |
| Storage  | Volume Docker    | Pasta `./uploads` persistida no host               |
| Android  | Kotlin + Material3 | App nativo para Android 15+                      |

O smartphone acessa a porta 80 pelo IP local. O nginx serve o frontend e encaminha `/api/*` internamente para o FastAPI — sem IP hardcoded no código.

## Pré-requisitos

### Servidor
- [Docker](https://docs.docker.com/get-docker/) e [Docker Compose](https://docs.docker.com/compose/install/)
- Ou: Python 3.12+ com [uv](https://docs.astral.sh/uv/) e Node 20+

### App Android
- Android Studio (para compilar)
- Android 15+ (minSdk 35)

## Como executar o servidor

### Com Docker (recomendado)

```bash
git clone https://github.com/AniltonC/wisl_upload.git
cd wisl_upload
docker compose up --build
```

O build do frontend ocorre na primeira execução. Nas seguintes, use apenas:

```bash
docker compose up
```

Acesse pelo computador em `http://localhost` ou pelo smartphone em `http://<IP-do-computador>`.

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

### Parar os containers

```bash
docker compose down
```

Os arquivos em `./uploads` persistem entre reinicializações.

## Como descobrir o IP local do computador

```bash
# Linux
ip route get 1 | awk '{print $7; exit}'

# macOS
ipconfig getifaddr en0

# Windows
ipconfig
```

No smartphone (web ou app Android), use `http://<IP>` ou `http://<IP>/api`.

## App Android

O app está no diretório `android/`. Abra-o no Android Studio, conecte um dispositivo com Android 15 ou superior e execute via Run.

Na tela inicial, informe o endereço do servidor no formato `http://192.168.x.x/api` e toque em **Connect**.

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
