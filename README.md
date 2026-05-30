# Rinha de Backend 2026

**Score: 5705.94 — p99 1.97 ms · FP 0 · FN 0 · E 0**

---

## Stack

| Componente | Tecnologia |
|---|---|
| Runtime | Java 25 · GraalVM Native Image (`-O3 -march=native`) |
| Framework | Spring Boot 4.0.6 · Virtual Threads |
| Serialização | Jackson 3.x |
| Load balancer | Nginx (least_conn) |

---

## Arquitetura

```
cliente
  └─► nginx :9999  (least_conn)
        ├─► api-1 :8080
        └─► api-2 :8080
```

Cada instância é completamente independente — sem banco de dados, sem comunicação entre si. O índice de busca fica inteiramente em memória.

### Limites de recursos

| Serviço | CPU | Memória |
|---|---|---|
| nginx   | 0.1  | 10 MB  |
| api-1   | 0.45 | 165 MB |
| api-2   | 0.45 | 165 MB |
| **Total** | **1.0** | **340 MB** |

---

## Fluxo de uma requisição

```
POST /fraud-score
         │
         ▼
  VectorizationService
  payload JSON → float[14]
  (14 dimensões normalizadas)
         │
         ▼
  KnnSearchService.search()
  float[14] → short[14]  (quantização int16 ×10000)
         │
         ▼
  IVF + bbox repair
  busca exata k=5 nos 3M vetores
  (escaneia ~5–15% dos dados)
         │
         ▼
  fraud_score = fraudes_entre_5_vizinhos / 5
  approved    = fraud_score < 0.6
         │
         ▼
  { "approved": true|false, "fraud_score": 0.0..1.0 }
```

---

## Algoritmo de busca — IVF com bbox repair

O desafio central é fazer KNN exato (k=5) sobre 3 milhões de vetores de 14 dimensões dentro do orçamento de memória e com p99 baixo.

### Por que não brute-force?

Varrer 3M vetores por query é correto, mas caro em p99 sob carga. Por outro lado, aproximações (HNSW, subsample) geram FP/FN que destroem o `det_score`.

### A solução: IVF + repair admissível

**Build-time (dentro do `docker build`):**

1. Quantiza os 3M vetores de `float32` para `int16` com escala ×10000.
2. Treina K=2048 centroides (k-means++ em amostra de 200K, 12 iterações de Lloyd).
3. Divide clusters com mais de 1024 vetores via sub-k-means — limita o pior caso de varredura.
4. Reordena os vetores em ordem clustered e calcula, por cluster, o centroide real e a bounding-box (min/max por dimensão).
5. Serializa tudo em `refs.bin` (~95 MB, ~3900 clusters finais).

**Runtime (por query):**

```
1. Quantiza a query (int16 ×10000)
2. Encontra o cluster-semente (centroide mais próximo)
3. Varre o cluster-semente → top-5 inicial
4. Para cada outro cluster:
     lb = lower-bound L2² da query à bbox do cluster
     se lb <= pior_distância_atual → varre o cluster
     senão → poda (nunca pode conter um vizinho melhor)
5. Retorna os 5 vizinhos exatos
```

O lower-bound de bbox é **admissível** — nunca superestima a distância real. Logo, qualquer cluster que pudesse conter um vizinho verdadeiro é varrido. O resultado é **idêntico ao brute-force** varrendo ~5–15% dos dados.

### Desempate determinístico

O ground-truth ordena por distância e desempata pelo índice original do vetor. O `Top5` interno usa exatamente a mesma regra: `(dist < piorDist) || (dist == piorDist && origId < piorOrigId)`.

### Decisão de fraude

```
fraud_score = fc / 5          (fc = vizinhos com label "fraud")
approved    = 5·fc < 3·K      (equivalente a fc/5 < 0.6, sem arredondamento float)
```

A comparação inteira evita o bug clássico de `3/5 = 0.5999... < 0.6f` que aprovaria fraudes com exatamente 3 vizinhos fraudulentos.

---

## Vetorização (14 dimensões)

| # | Campo | Fórmula |
|---|---|---|
| 0 | `amount` | `clamp(amount / 10000)` |
| 1 | `installments` | `clamp(installments / 12)` |
| 2 | `amount_vs_avg` | `clamp((amount / avg_amount) / 10)` |
| 3 | `hour_of_day` | `hour_UTC / 23` |
| 4 | `day_of_week` | `(dayOfWeek - 1) / 6` (seg=0, dom=6) |
| 5 | `minutes_since_last_tx` | `clamp(min / 1440)` ou **−1** se sem histórico |
| 6 | `km_from_last_tx` | `clamp(km / 1000)` ou **−1** se sem histórico |
| 7 | `km_from_home` | `clamp(km / 1000)` |
| 8 | `tx_count_24h` | `clamp(count / 20)` |
| 9 | `is_online` | `1` ou `0` |
| 10 | `card_present` | `1` ou `0` |
| 11 | `unknown_merchant` | `1` se desconhecido, `0` se conhecido |
| 12 | `mcc_risk` | lookup em `mcc_risk.json` (padrão 0.5) |
| 13 | `merchant_avg_amount` | `clamp(avg_amount / 10000)` |

O parser ISO-8601 é implementado manualmente (sem `Instant.parse`) — ~10× mais rápido e zero alocação.

---

## Carregamento do índice

O `refs.bin` é embutido na imagem em `/app/refs.bin` (gerado no `docker build`). Ao subir, `DatasetLoader` carrega o arquivo em uma virtual thread disparada no `ApplicationReadyEvent`:

- A API responde em ~1 s (GraalVM native sem JVM warmup).
- `/ready` retorna `503` enquanto o índice carrega.
- `/ready` retorna `200` quando pronto — o nginx/k6 aguarda esse sinal antes de começar o teste.
