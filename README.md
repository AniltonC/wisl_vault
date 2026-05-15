# File Storage

Aplicação web simples para armazenamento e upload de arquivos, projetada para testes de upload em smartphones na rede local. Funciona como um Google Drive pessoal, sem autenticação, executando inteiramente em containers Docker.

## Funcionalidades

- Upload de arquivos via toque (mobile) ou drag & drop (desktop)
- Seleção múltipla de arquivos com envio sequencial
- Barra de progresso em tempo real com percentual
- Listagem de arquivos com nome, tamanho e data de modificação
- Download e exclusão de arquivos individuais
- Interface responsiva, otimizada para smartphones

## Arquitetura

```
wisl_upload/
├── docker-compose.yml
├── uploads/                  # Arquivos armazenados (criado automaticamente)
├── backend/                  # API FastAPI
│   ├── Dockerfile
│   ├── requirements.txt
│   └── main.py
└── frontend/                 # Interface React (servida via nginx)
    ├── Dockerfile
    ├── nginx.conf
    ├── vite.config.js
    └── src/
        ├── App.jsx
        └── index.css
```

| Camada   | Tecnologia     | Papel                                              |
|----------|----------------|----------------------------------------------------|
| Frontend | React + Vite   | Interface de usuário                               |
| Proxy    | nginx          | Serve o React e repassa `/api/*` para o backend    |
| Backend  | FastAPI        | Upload, listagem, download e exclusão de arquivos  |
| Storage  | Volume Docker  | Pasta `./uploads` persistida no host               |

O smartphone acessa o site na porta 80. O nginx serve o frontend e encaminha as chamadas `/api/` internamente para o FastAPI — sem necessidade de IP hardcoded no código.

## Pré-requisitos

- [Docker](https://docs.docker.com/get-docker/)
- [Docker Compose](https://docs.docker.com/compose/install/)

## Como executar

### 1. Clonar o repositório

```bash
git clone <url-do-repositorio>
cd wisl_upload
```

### 2. Subir os containers

```bash
docker compose up --build
```

O build do frontend (instalação de dependências + compilação) ocorre na primeira execução e leva alguns minutos. Nas execuções seguintes, use apenas:

```bash
docker compose up
```

### 3. Acessar pelo computador

Abra o navegador em:

```
http://localhost
```

### 4. Acessar pelo smartphone

O smartphone precisa estar conectado na **mesma rede Wi-Fi** que o computador.

Descubra o IP local do computador:

```bash
# Linux / macOS
ip route get 1 | awk '{print $7; exit}'

# macOS (alternativo)
ipconfig getifaddr en0

# Windows
ipconfig
```

No smartphone, acesse:

```
http://<IP-do-computador>
```

Exemplo: `http://192.168.1.42`

## Parar os containers

```bash
docker compose down
```

Os arquivos enviados ficam salvos na pasta `./uploads` e persistem entre reinicializações.

## Endpoints da API

| Método   | Rota                  | Descrição                  |
|----------|-----------------------|----------------------------|
| `GET`    | `/files`              | Lista todos os arquivos     |
| `POST`   | `/upload`             | Envia um arquivo            |
| `GET`    | `/files/{filename}`   | Faz download de um arquivo  |
| `DELETE` | `/files/{filename}`   | Exclui um arquivo           |

## Desenvolvimento local (sem Docker)

### Backend

```bash
cd backend
pip install -r requirements.txt
UPLOAD_DIR=./uploads uvicorn main:app --reload
```

### Frontend

```bash
cd frontend
npm install
npm run dev
```

O `vite.config.js` já está configurado para fazer proxy de `/api` para `http://localhost:8000`, então o frontend em modo dev funciona sem alterar nenhuma URL.
