from __future__ import annotations

import os
from elasticsearch import Elasticsearch, helpers


def get_es() -> Elasticsearch:
    # 예: http://elasticsearch:9200
    url = os.environ.get("ELASTICSEARCH_URL", "http://elasticsearch:9200")
    return Elasticsearch(url)


def bulk_index(index: str, docs: list[dict]):
    if not docs:
        return
    es = get_es()
    actions = [
        {
            "_op_type": "index",
            "_index": index,
            "_source": d,
        }
        for d in docs
    ]
    helpers.bulk(es, actions)
