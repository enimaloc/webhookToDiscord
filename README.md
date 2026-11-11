# webhookToDiscord

Relais de webhooks générique : `POST /webhook/<id>` exécute un script JavaScript (GraalJS, sandboxé) propre à chaque entrée, qui transforme la requête reçue en messages envoyés à des webhooks Discord. Un panneau protégé par mot de passe permet de gérer les entrées, tester les scripts et consulter l'historique. Stockage PostgreSQL, compatible multi-pods.

## Contrat du script

Le script définit une fonction `handle(request)` qui retourne un tableau `[{url, message, method?, saveId?, forget?}]` :

```js
function handle(request) {
  return [{
    url: "https://discord.com/api/webhooks/<id>/<token>",
    message: { content: "Push de " + request.json.pusher.name }
  }];
}
```

- `url` : URL de webhook Discord valide ; `message` : charge utile Discord (`content`, `embeds`, ...).
- `method` (optionnel, `POST` par défaut) : `POST` crée un message, `PATCH` le modifie, `DELETE` le supprime (`message` ignoré). `PATCH` et `DELETE` exigent une URL `.../webhooks/<id>/<token>/messages/<message_id>`, `POST` l'interdit.
- Réponse : si Discord renvoie l'objet message (`POST` avec `?wait=true`, `PATCH`), il est enregistré dans l'historique (`sends[].message`, avec son `id`) ; sans `?wait=true`, Discord répond 204 sans corps.
- `request` : `method`, `headers` et `query` (maps nom → liste de valeurs), `body` (texte UTF-8) et `json` (corps analysé, `null` si ce n'est pas du JSON), `store` (mémoire de l'entrée, voir ci-dessous).
- Mémoire (`request.store`) : objet clé → valeur propre à chaque entrée, conservé entre les exécutions. Un message avec `saveId: "<clé>"` y enregistre l'`id` du message renvoyé par Discord (`PATCH`, ou `POST` avec `?wait=true`, obligatoire dans ce cas) ; `forget: "<clé>"` supprime la clé. Les deux ne s'appliquent qu'après un envoi réussi, jamais en aperçu. Les clés non réécrites depuis `HISTORY_RETENTION_DAYS` jours sont purgées.
- Limites : 25 messages max par exécution, résultat de 1 Mo max, timeout `SCRIPT_TIMEOUT_MS`, 3 tentatives max par envoi Discord.

## Configuration

| Variable | Défaut | Description |
|---|---|---|
| `DATABASE_URL` | - (obligatoire) | URL JDBC PostgreSQL (`jdbc:postgresql://host:5432/db`) |
| `DB_USER` / `DB_PASSWORD` | - | Identifiants de la base |
| `ADMIN_PASSWORD` | - (obligatoire) | Mot de passe du panneau |
| `SESSION_SECRET` | aléatoire par processus | Secret de signature des sessions. **Obligatoire avec plusieurs replicas** (même valeur partout), recommandé dans tous les cas |
| `PUBLIC_BASE_URL` | `http://localhost:<PORT>` | URL publique affichée dans le panneau |
| `PORT` | `8080` | Port HTTP |
| `SCRIPT_TIMEOUT_MS` | `5000` | Durée maximale d'un script |
| `HISTORY_RETENTION_DAYS` | `30` | Durée de conservation de l'historique |
| `MAX_BODY_BYTES` | `1048576` | Taille maximale du corps reçu |

## Lancer en local

Prérequis : JDK 25, Docker (tests Testcontainers) et une base PostgreSQL.

```sh
export DATABASE_URL=jdbc:postgresql://localhost:5432/postgres DB_USER=postgres DB_PASSWORD=pw ADMIN_PASSWORD=secret
./gradlew run
./gradlew test
```

Le panneau est servi sur `http://localhost:8080`, la sonde de santé sur `/health`.

## Déploiement

Image : `docker build -t webhook-to-discord .` (publiée sur `ghcr.io/<owner>/webhooktodiscord` à chaque tag `v*`).

Des manifestes Kubernetes d'exemple sont dans `k8s/` (Deployment à 2 replicas, Service, Secret) :

```sh
cp k8s/secret.example.yaml k8s/secret.yaml   # puis remplacer les valeurs
kubectl apply -f k8s/secret.yaml -f k8s/deployment.yaml -f k8s/service.yaml
```
