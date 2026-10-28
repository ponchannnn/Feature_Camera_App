"""自己署名の証明書の作成と、アプリに固定する公開鍵のフィンガープリント。"""

from __future__ import annotations

import base64
import datetime
import hashlib
import ipaddress
import os
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID

VALID_DAYS = 3650


def _san_entry(host: str) -> x509.GeneralName:
    try:
        return x509.IPAddress(ipaddress.ip_address(host))
    except ValueError:
        return x509.DNSName(host)


def create_self_signed(cert_file: Path, key_file: Path, hosts: list[str], reuse_key: bool = True) -> None:
    """
    hosts(IP アドレスまたはホスト名)を SAN に入れた証明書を作る。
    既存の鍵があれば使い回すので、IP アドレスが変わって作り直してもフィンガープリントは変わらない。
    """
    key_file.parent.mkdir(parents=True, exist_ok=True)
    if reuse_key and key_file.exists():
        key = serialization.load_pem_private_key(key_file.read_bytes(), password=None)
    else:
        key = ec.generate_private_key(ec.SECP256R1())
        pem = key.private_bytes(
            serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()
        )
        key_file.write_bytes(pem)
        try:
            os.chmod(key_file, 0o600)
        except OSError:
            pass

    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, hosts[0] if hosts else "zoomcapture-server")])
    now = datetime.datetime.now(datetime.timezone.utc)
    cert = (
        x509.CertificateBuilder()
        .subject_name(name)
        .issuer_name(name)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - datetime.timedelta(days=1))
        .not_valid_after(now + datetime.timedelta(days=VALID_DAYS))
        .add_extension(x509.SubjectAlternativeName([_san_entry(h) for h in hosts]), critical=False)
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .sign(key, hashes.SHA256())
    )
    cert_file.write_bytes(cert.public_bytes(serialization.Encoding.PEM))


def public_key_pin(cert_file: Path) -> str:
    """証明書の公開鍵(SubjectPublicKeyInfo)の SHA-256 を Base64 にしたもの。Android 側でこれと比べる"""
    cert = x509.load_pem_x509_certificate(cert_file.read_bytes())
    spki = cert.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    return base64.b64encode(hashlib.sha256(spki).digest()).decode("ascii")


def cert_hosts(cert_file: Path) -> list[str]:
    cert = x509.load_pem_x509_certificate(cert_file.read_bytes())
    san = cert.extensions.get_extension_for_class(x509.SubjectAlternativeName).value
    return [str(v) for v in san.get_values_for_type(x509.IPAddress)] + san.get_values_for_type(x509.DNSName)
