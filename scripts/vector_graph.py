#!/usr/bin/env python3
"""Render a read-only graph from the embeddings stored in MCP Memory's PostgreSQL.

Run on the Debian VM:
    python3 scripts/vector_graph.py --output vector-graph.html

The program reads through the existing PostgreSQL Docker container. It does not
change the database or start a web service. The HTML contains memory text, 2D
coordinates and similarity scores, but not the original 384-dimensional vectors.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import subprocess
import sys
from pathlib import Path


TEMPLATE = Path(__file__).with_name("vector_graph_template.html")


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path("vector-graph.html"), help="standalone HTML output path")
    parser.add_argument("--container", default="mcp-memory-db-1", help="PostgreSQL Docker container")
    parser.add_argument("--db-user", default="memory", help="PostgreSQL user inside the container")
    parser.add_argument("--db-name", default="memory", help="PostgreSQL database")
    parser.add_argument("--limit", type=int, default=300, help="most recent memories to include (1-500)")
    parser.add_argument("--neighbors", type=int, default=3, help="nearest neighbors retained per memory (1-10)")
    parser.add_argument("--active-only", action="store_true", help="omit archived and superseded memories")
    args = parser.parse_args()
    if not 1 <= args.limit <= 500:
        parser.error("--limit must be between 1 and 500")
    if not 1 <= args.neighbors <= 10:
        parser.error("--neighbors must be between 1 and 10")
    return args


def read_database(args: argparse.Namespace) -> dict:
    condition = "WHERE active AND NOT archived AND NOT superseded" if args.active_only else ""
    sql = f"""
WITH chosen AS (
    SELECT id, type, content, embedding::text AS embedding,
           embedding_provider, embedding_model, embedding_version,
           embedding_dimensions, active, archived, superseded, stale,
           supersedes_memory_id, created_at
    FROM memories
    {condition}
    ORDER BY created_at DESC, id
    LIMIT {args.limit}
)
SELECT json_build_object(
    'total', (SELECT count(*) FROM memories {condition}),
    'memories', coalesce((SELECT json_agg(row_to_json(m) ORDER BY m.created_at DESC, m.id) FROM chosen m), '[]'::json),
    'links', coalesce((
        SELECT json_agg(row_to_json(l) ORDER BY l.from_id, l.to_id, l.relation)
        FROM (
            SELECT from_id, to_id, relation
            FROM memory_links
            WHERE from_id IN (SELECT id FROM chosen)
              AND to_id IN (SELECT id FROM chosen)
        ) l
    ), '[]'::json)
)::text;
"""
    command = [
        "docker", "exec", args.container, "psql", "-X", "-q", "-A", "-t",
        "-v", "ON_ERROR_STOP=1", "-U", args.db_user, "-d", args.db_name,
        "-c", sql,
    ]
    try:
        result = subprocess.run(command, capture_output=True, text=True, check=True, timeout=60)
    except FileNotFoundError as exc:
        raise RuntimeError("docker command was not found") from exc
    except subprocess.CalledProcessError as exc:
        raise RuntimeError(f"database read failed: {exc.stderr.strip() or exc.stdout.strip()}") from exc
    except subprocess.TimeoutExpired as exc:
        raise RuntimeError("database read timed out after 60 seconds") from exc
    try:
        return json.loads(result.stdout.strip())
    except json.JSONDecodeError as exc:
        raise RuntimeError("PostgreSQL did not return valid JSON") from exc


def normalized(vector: list[float]) -> list[float]:
    norm = math.sqrt(sum(value * value for value in vector))
    if not math.isfinite(norm) or norm == 0:
        raise ValueError("a memory has an empty or invalid embedding")
    return [value / norm for value in vector]


def centered_gram(vectors: list[list[float]]) -> list[list[float]]:
    count = len(vectors)
    dimensions = len(vectors[0])
    mean = [sum(vector[k] for vector in vectors) / count for k in range(dimensions)]
    centered = [[value - mean[k] for k, value in enumerate(vector)] for vector in vectors]
    return [[sum(a * b for a, b in zip(centered[i], centered[j])) for j in range(count)] for i in range(count)]


def leading_axes(gram: list[list[float]], axes: int = 2) -> list[list[float]]:
    count = len(gram)
    eigenvectors: list[list[float]] = []
    coordinates: list[list[float]] = []
    for axis in range(axes):
        vector = [math.sin((i + 1) * (axis + 1) * 1.718) for i in range(count)]
        for _ in range(250):
            multiplied = [sum(row[j] * vector[j] for j in range(count)) for row in gram]
            for previous in eigenvectors:
                projection = sum(a * b for a, b in zip(multiplied, previous))
                multiplied = [value - projection * basis for value, basis in zip(multiplied, previous)]
            length = math.sqrt(sum(value * value for value in multiplied))
            if length < 1e-12:
                vector = [0.0] * count
                break
            next_vector = [value / length for value in multiplied]
            if sum((a - b) ** 2 for a, b in zip(vector, next_vector)) < 1e-18:
                vector = next_vector
                break
            vector = next_vector
        eigenvectors.append(vector)
        eigenvalue = max(0.0, sum(vector[i] * sum(gram[i][j] * vector[j] for j in range(count)) for i in range(count)))
        coordinates.append([math.sqrt(eigenvalue) * value for value in vector])
    scale = max((abs(value) for axis_values in coordinates for value in axis_values), default=0.0) or 1.0
    return [[round(coordinates[0][i] / scale, 6), round(coordinates[1][i] / scale, 6)] for i in range(count)]


def prepare(snapshot: dict, neighbors: int) -> dict:
    rows = snapshot["memories"]
    if not rows:
        raise RuntimeError("there are no memories to draw")
    vectors = []
    expected_dimensions = None
    expected_model = None
    for row in rows:
        vector = [float(value) for value in json.loads(row.pop("embedding"))]
        if expected_dimensions is None:
            expected_dimensions = len(vector)
            expected_model = (row["embedding_provider"], row["embedding_model"], row["embedding_version"])
        if len(vector) != expected_dimensions or len(vector) != row["embedding_dimensions"]:
            raise RuntimeError("memories use different embedding dimensions")
        if (row["embedding_provider"], row["embedding_model"], row["embedding_version"]) != expected_model:
            raise RuntimeError("memories use different embedding models or versions; one map cannot compare them")
        vectors.append(normalized(vector))
    coordinates = leading_axes(centered_gram(vectors))
    for row, xy in zip(rows, coordinates):
        row["x"], row["y"] = xy

    all_pairs = []
    for i in range(len(rows)):
        for j in range(i + 1, len(rows)):
            score = sum(a * b for a, b in zip(vectors[i], vectors[j]))
            all_pairs.append({"source": i, "target": j, "similarity": round(score, 5)})
    nearest = {}
    for i in range(len(rows)):
        ranked = sorted((pair for pair in all_pairs if i in (pair["source"], pair["target"])), key=lambda pair: pair["similarity"], reverse=True)
        for pair in ranked[:neighbors]:
            nearest[(pair["source"], pair["target"])] = pair
    edges = sorted(nearest.values(), key=lambda pair: pair["similarity"], reverse=True)

    index = {str(row["id"]): i for i, row in enumerate(rows)}
    links = []
    seen_links = set()
    for item in snapshot["links"]:
        source, target = index.get(str(item["from_id"])), index.get(str(item["to_id"]))
        if source is None or target is None:
            continue
        relation = item["relation"]
        key = (source, target, relation)
        if key not in seen_links:
            links.append({"source": source, "target": target, "relation": relation})
            seen_links.add(key)
    for i, row in enumerate(rows):
        target = index.get(str(row["supersedes_memory_id"]))
        key = (i, target, "SUPERSEDES")
        if target is not None and key not in seen_links:
            links.append({"source": i, "target": target, "relation": "SUPERSEDES"})
            seen_links.add(key)

    return {
        "total": snapshot["total"],
        "count": len(rows),
        "dimensions": expected_dimensions,
        "models": sorted({row["embedding_model"] for row in rows}),
        "nodes": rows,
        "edges": edges,
        "links": links,
    }


def render_html(data: dict, live_url: str = "") -> str:
    template = TEMPLATE.read_text(encoding="utf-8")
    payload = json.dumps(data, ensure_ascii=False, separators=(",", ":")).replace("<", "\\u003c")
    if "__GRAPH_DATA__" not in template or "__LIVE_URL__" not in template:
        raise RuntimeError("HTML template is missing a data placeholder")
    return template.replace("__GRAPH_DATA__", payload).replace("__LIVE_URL__", live_url)


def write_html(data: dict, output: Path) -> None:
    html = render_html(data)
    output = output.expanduser().resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    temp = output.with_name(output.name + ".tmp")
    previous_umask = os.umask(0o077)
    try:
        temp.write_text(html, encoding="utf-8")
        temp.replace(output)
        os.chmod(output, 0o600)
    finally:
        os.umask(previous_umask)
        if temp.exists():
            temp.unlink()


def main() -> int:
    args = arguments()
    try:
        data = prepare(read_database(args), args.neighbors)
        write_html(data, args.output)
    except (RuntimeError, ValueError, OSError) as exc:
        print(f"vector graph: {exc}", file=sys.stderr)
        return 1
    print(f"Created {args.output.resolve()} with {data['count']} memories, {len(data['edges'])} similarity candidates and {len(data['links'])} recorded relations")
    if data["total"] > data["count"]:
        print(f"Showing the {data['count']} newest of {data['total']} memories; increase --limit to include more")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
