"""Test-only arbitrary-precision desktop reference; never used by the app.

Edwards25519 equations from RFC8032 section 5.1, BoringSSL SPAKE transcript
from AOSP f9e0b0e17fabac35627f18f94a8954c3857784ac spake25519.cc.
The pinned upstream test has no fixed vectors (its TODO states this). This
independent implementation generates our deterministic regression fixture.
This is deliberately slow/nonconstant-time and must not handle real secrets.
"""
import hashlib
import hmac
import json

P = 2**255 - 19
L = 2**252 + 27742317777372353535851937790883648493
D = -121665 * pow(121666, -1, P) % P


def decode(encoded):
    n = int.from_bytes(encoded, 'little'); y = n % (2**255)
    x2 = (y*y-1) * pow(D*y*y+1, -1, P) % P
    x = pow(x2, (P+3)//8, P)
    if x*x % P != x2: x = x * pow(2, (P-1)//4, P) % P
    assert x*x % P == x2
    if x % 2 != n >> 255: x = P-x
    return x, y


def encode(point):
    x,y = point
    return (y + (x % 2)*2**255).to_bytes(32, 'little')


def add(a, b):
    x,y = a; u,v = b; t = D*x*u*y*v % P
    return ((x*v+u*y)*pow(1+t, -1, P)%P,
            (y*v+x*u)*pow(1-t, -1, P)%P)


def mul(point, scalar):
    result = (0,1)
    while scalar:
        if scalar & 1: result = add(result, point)
        point = add(point,point); scalar >>= 1
    return result


password = bytes.fromhex('353932373831e63dd959651c211600f3b6561d0b9d90af09d0a4a453ee2059a480cc7c5a94d4d48933f9fff5fe43317d52fa7bff8f8bc4f3488b8007330fec7c7edc91c20e5d')
alice_entropy = bytes.fromhex('47f6c458e5f062db8427d2d9bb20c954a76d6943959756a18d11d45e1ad190f980a86d185a93ca1d3025c5febe3aac4045b34a39b1f511385ca97fc4332137f3')
bob_entropy = bytes.fromhex('a6bf9f9bf7819e0ded8c2dd82a1aa38acb2f8a6403429cff33d64ea9c40439d5fd7029811a5f5a8f7c89c8b44ac0b421f6b24ca2ba18d2069995831730cd8c5a')
base = decode(bytes.fromhex('58'+'66'*31))
point_m = decode(hashlib.sha256(b'edwards25519 point generation seed (M)').digest())
point_n = decode(hashlib.sha256(b'edwards25519 point generation seed (N)').digest())
a = int.from_bytes(alice_entropy,'little') % L * 8
b = int.from_bytes(bob_entropy,'little') % L * 8
hashed = hashlib.sha512(password).digest()
w = int.from_bytes(hashed,'little') % L
while w % 8: w += L
msg_a = encode(add(mul(base,a),mul(point_m,w)))
msg_b = encode(add(mul(base,b),mul(point_n,w)))
dh = encode(mul(base,a*b))
parts = [b'adb pair client\0',b'adb pair server\0',msg_a,msg_b,dh,hashed]
key = hashlib.sha512(b''.join(len(p).to_bytes(8,'little')+p for p in parts)).digest()
prk = hmac.digest(bytes(32),key,'sha256')
aes = hmac.digest(prk,b'adb pairing_auth aes-128-gcm key\x01','sha256')[:16]
print(json.dumps({k:v.hex() for k,v in dict(password=password,alice_entropy=alice_entropy,bob_entropy=bob_entropy,alice_message=msg_a,bob_message=msg_b,shared_key=key,aes_key=aes).items()},indent=2))
