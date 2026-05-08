# Rinha de Backend 2026 — Documentação da Solução

## Stack

| Componente | Tecnologia |
|---|---|
| Framework | Spring Boot 4.0.6 |
| Runtime | Java 25 com Virtual Threads (`spring.threads.virtual.enabled=true`) |
| Serialização | Jackson 3.x (`tools.jackson.*`) |
| Load balancer | Nginx (least_conn) |
| Containerização | Docker multi-stage (Maven + eclipse-temurin:21-jre-alpine) |

---

## Arquitetura

```
cliente
  └─► nginx :9999  (least_conn)
        ├─► api-1 :8080
        └─► api-2 :8080
```

Cada instância carrega o dataset completo em memória e responde de forma independente — sem banco de dados, sem comunicação entre instâncias.

### Limites de recursos (docker-compose)

| Serviço | CPU | Memória |
|---|---|---|
| nginx | 0.1 | 10 MB |
| api-1 | 0.45 | 165 MB |
| api-2 | 0.45 | 165 MB |
| **Total** | **1.0** | **340 MB** |

---

## Fluxo de uma requisição

```
POST /fraud-score
      │
      ▼
VectorizationService.vectorize()
  └─ payload → float[14]  (14 dimensões normalizadas)
      │
      ▼
KnnSearchService.quantizeVector()
  └─ float[14] → byte[14]  (quantização int8)
      │
      ▼
KnnSearchService.evaluate()
  └─ brute-force KNN (k=5) no array de 3M vetores int8
      │
      ▼
fraud_score = fraudes_entre_vizinhos / 5
approved    = fraud_score < 0.6
      │
      ▼
{"approved": true|false, "fraud_score": 0.0..1.0}
```

---

## Vetorização (14 dimensões)

Implementada em `VectorizationService`. Converte o payload JSON em um vetor de floats normalizado no intervalo `[0.0, 1.0]`, com exceção dos índices 5 e 6 que usam `-1.0` como sentinela quando `last_transaction` é null.

| Índice | Campo | Fórmula |
|---|---|---|
| 0 | `amount` | `clamp(amount / 10000)` |
| 1 | `installments` | `clamp(installments / 12)` |
| 2 | `amount_vs_avg` | `clamp((amount / avg_amount) / 10)` |
| 3 | `hour_of_day` | `hour_UTC / 23` |
| 4 | `day_of_week` | `(day - 1) / 6` (seg=0, dom=6) |
| 5 | `minutes_since_last_tx` | `clamp(minutos / 1440)` ou **-1** |
| 6 | `km_from_last_tx` | `clamp(km / 1000)` ou **-1** |
| 7 | `km_from_home` | `clamp(km_from_home / 1000)` |
| 8 | `tx_count_24h` | `clamp(tx_count_24h / 20)` |
| 9 | `is_online` | `1` ou `0` |
| 10 | `card_present` | `1` ou `0` |
| 11 | `unknown_merchant` | `1` se desconhecido, `0` se conhecido |
| 12 | `mcc_risk` | valor de `mcc_risk.json` (padrão 0.5) |
| 13 | `merchant_avg_amount` | `clamp(avg_amount / 10000)` |

As constantes de normalização vêm de `normalization.json` (embutido no JAR).

---

## Quantização int8

O dataset tem 3 milhões de vetores × 14 dimensões. Armazenado como `float32` isso seria **168 MB** por instância — acima do limite de 165 MB.

A solução é a **quantização int8**: cada `float` em `[0.0, 1.0]` é mapeado para um `byte` em `[0, 127]`. O sentinela `-1.0` vira `-128` (mínimo de `byte` com sinal).

```
float  →  byte
 0.0   →    0
 0.5   →   64  (round(0.5 * 127))
 1.0   →  127
-1.0   → -128  (sentinela)
```

Resultado: **42 MB** por instância — cabe com folga dentro do limite.

A distância euclidiana ao quadrado entre dois vetores int8 preserva a **ordem** das distâncias em float, portanto o KNN retorna os mesmos k vizinhos que retornaria com float32.

---

## Busca KNN (k=5, brute-force)

Implementada em `KnnSearchService`. Os vetores são armazenados em um array `byte[]` plano (flat), indexado como `vectors[i * 14 .. i * 14 + 13]`.

Para cada query, percorre os 3M vetores mantendo um **max-heap de tamanho 5**. Ao final do loop o heap contém os 5 vizinhos mais próximos.

```java
// distância euclidiana ao quadrado em int8
long sum = 0;
for (int i = 0; i < 14; i++) {
    long d = query[i] - vectors[base + i];
    sum += d * d;
}
```

**Decisão de fraude:**

```
fraud_score = número_de_fraudes_nos_5_vizinhos / 5
approved    = fraud_score < 0.6
```

O mesmo algoritmo (k=5, distância euclidiana) usado pelo gabarito do teste, o que garante precisão máxima de detecção.

---

## Carregamento do dataset

O arquivo `references.json.gz` (~16 MB comprimido, ~284 MB JSON) é embutido no JAR em `src/main/resources/` e carregado via `getClass().getResourceAsStream("/references.json.gz")`.

O carregamento acontece em uma **virtual thread** disparada no `ApplicationReadyEvent`, ou seja:

- A aplicação sobe e começa a responder requisições em ~1.5s
- `/ready` retorna `503` enquanto o dataset está sendo carregado
- `/ready` retorna `200` quando o dataset está pronto (após ~3s no hardware de desenvolvimento)
- Qualquer requisição para `/fraud-score` durante o carregamento também retorna `503`

O parser usa a API de streaming do Jackson (`objectMapper.createParser(InputStream)`) para processar os 3M registros sem nunca construir uma árvore JSON em memória.

---

## Estrutura do projeto

```
rinha-backend-2026/
├── src/main/
│   ├── java/com/dev/rinha_backend_2026/
│   │   ├── RinhaBackend2026Application.java
│   │   ├── web/
│   │   │   └── FraudController.java          # GET /ready · POST /fraud-score
│   │   ├── domain/
│   │   │   ├── TransactionRequest.java        # DTOs de entrada (records)
│   │   │   └── FraudResponse.java             # { approved, fraud_score }
│   │   ├── service/
│   │   │   ├── VectorizationService.java      # payload → float[14]
│   │   │   ├── KnnSearchService.java          # busca KNN brute-force int8
│   │   │   └── FraudDetectionService.java     # orquestra vetorização + KNN
│   │   └── config/
│   │       ├── NormalizationConfig.java       # record com as constantes
│   │       ├── AppConfig.java                 # carrega normalization.json e mcc_risk.json
│   │       └── DatasetLoader.java             # carrega references.json.gz → KnnSearchService
│   └── resources/
│       ├── application.yaml
│       ├── references.json.gz                 # 3M vetores rotulados (embutido no JAR)
│       ├── mcc_risk.json                      # scores de risco por MCC
│       └── normalization.json                 # constantes de normalização
├── Dockerfile                                 # multi-stage: Maven build + JRE Alpine
├── docker-compose.yml                         # nginx + api-1 + api-2
├── nginx.conf                                 # least_conn, HTTP/1.1 keepalive
└── pom.xml
```

---

## Por que não HNSW

O plano original previa usar HNSW (Hierarchical Navigable Small World) para reduzir a latência de busca de O(N) para O(log N). A análise de memória inviabilizou:

| Componente | Memória (M=4) | Memória (M=8) |
|---|---|---|
| Vetores int8 | 42 MB | 42 MB |
| Grafo HNSW (conexões) | ~96 MB | ~192 MB |
| JVM base | ~30 MB | ~30 MB |
| **Total estimado** | **~168 MB** | **~264 MB** |

Com M=4 já ultrapassaria os 165 MB disponíveis. Com M=8 (mínimo recomendado para recall aceitável) ficaria fora de questão.

### Próximo passo para melhorar latência

**IVF (Inverted File Index) cluster-based search:**

- Dividir os 3M vetores em ~100 clusters via K-means durante o startup
- A cada query, calcular distância apenas para os 3-5 clusters mais próximos
- Reduz de 3M para ~90K comparações → estimativa de ~0.3ms por query
- Overhead de memória: ~14KB para centroides + ~6MB para atribuições de cluster

---

## Nota sobre Jackson 3.x

Spring Boot 4.x usa Jackson 3.x, que mudou o namespace dos pacotes:

| Jackson 2.x | Jackson 3.x |
|---|---|
| `com.fasterxml.jackson.databind.ObjectMapper` | `tools.jackson.databind.ObjectMapper` |
| `com.fasterxml.jackson.core.JsonParser` | `tools.jackson.core.JsonParser` |
| `com.fasterxml.jackson.core.type.TypeReference` | `tools.jackson.core.type.TypeReference` |
| `com.fasterxml.jackson.annotation.@JsonProperty` | permanece em `com.fasterxml.jackson.annotation` (compat) |
