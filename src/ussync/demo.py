"""Fuente sintética: usa el mismo descargador sin servicios ni cuentas reales."""

import httpx

from .models import Document
from .network import HTTP


def pdf(text):
    content = f"BT /F1 16 Tf 50 780 Td ({text}) Tj ET".encode("ascii")
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        b"<< /Length " + str(len(content)).encode() + b" >>\nstream\n" + content + b"\nendstream",
    ]
    data, offsets = b"%PDF-1.4\n", [0]
    for i, obj in enumerate(objects, 1):
        offsets.append(len(data))
        data += f"{i} 0 obj\n".encode() + obj + b"\nendobj\n"
    start = len(data)
    data += f"xref\n0 {len(offsets)}\n0000000000 65535 f \n".encode()
    for offset in offsets[1:]:
        data += f"{offset:010d} 00000 n \n".encode()
    return (
        data
        + f"trailer\n<< /Size {len(offsets)} /Root 1 0 R >>\nstartxref\n{start}\n%%EOF\n".encode()
    )


def transport():
    return HTTP(
        httpx.AsyncClient(
            transport=httpx.MockTransport(
                lambda request: httpx.Response(
                    200,
                    content=pdf("USSync - Documento de demostracion"),
                    headers={"Content-Type": "application/pdf"},
                )
            )
        )
    )


async def documents():
    for course in ("Álgebra de demostración", "Informática de demostración"):
        for filename in ("Materiales/Tema 1.pdf", "Documentación/Proyecto docente.pdf"):
            yield Document(
                f"demo:{course}:{filename}",
                "demo",
                course,
                tuple(filename.split("/")),
                "https://demo.invalid/document.pdf",
                revision="1",
                pdf=True,
            )
