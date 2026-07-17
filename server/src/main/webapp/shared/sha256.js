/*
 * Copyright 2026 Jason Harrop
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/* SHA-256 in plain JS, because the PKCE S256 challenge must be
 * computable where WebCrypto's crypto.subtle does not exist — it is
 * secure-context-gated, and the HTTP-on-LAN dev loop (a phone browsing
 * http://<LAN-IP>:18080) is not a secure context. auth.js prefers
 * crypto.subtle when present and falls back to this, keeping the
 * challenge method S256 everywhere (no downgrade to `plain`).
 * FIPS 180-4; verified against node:crypto over fixture vectors and
 * random lengths spanning the padding boundaries.
 *
 * Classic script exposing a `Sha256` global (auth.js is a classic
 * script and cannot import modules); under Node/CommonJS it exports
 * {sha256} for test harnesses.
 */
"use strict";

(function (root) {

    const K = [
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
    ];

    /** SHA-256 of a Uint8Array; returns a 32-byte Uint8Array. */
    function sha256(bytes) {
        const length = bytes.length;
        const total = (((length + 9) + 63) >> 6) << 6; // data + 0x80 + 64-bit length, block-aligned
        const buffer = new Uint8Array(total);
        buffer.set(bytes);
        buffer[length] = 0x80;
        const view = new DataView(buffer.buffer);
        view.setUint32(total - 8, Math.floor(length / 0x20000000)); // (length*8) >> 32
        view.setUint32(total - 4, (length << 3) >>> 0);

        let h0 = 0x6a09e667, h1 = 0xbb67ae85, h2 = 0x3c6ef372, h3 = 0xa54ff53a;
        let h4 = 0x510e527f, h5 = 0x9b05688c, h6 = 0x1f83d9ab, h7 = 0x5be0cd19;
        const w = new Int32Array(64);

        for (let block = 0; block < total; block += 64) {
            for (let t = 0; t < 16; t++) w[t] = view.getUint32(block + t * 4);
            for (let t = 16; t < 64; t++) {
                const x = w[t - 15], y = w[t - 2];
                const s0 = ((x >>> 7) | (x << 25)) ^ ((x >>> 18) | (x << 14)) ^ (x >>> 3);
                const s1 = ((y >>> 17) | (y << 15)) ^ ((y >>> 19) | (y << 13)) ^ (y >>> 10);
                w[t] = (w[t - 16] + s0 + w[t - 7] + s1) | 0;
            }
            let a = h0, b = h1, c = h2, d = h3, e = h4, f = h5, g = h6, h = h7;
            for (let t = 0; t < 64; t++) {
                const S1 = ((e >>> 6) | (e << 26)) ^ ((e >>> 11) | (e << 21)) ^ ((e >>> 25) | (e << 7));
                const ch = (e & f) ^ (~e & g);
                const temp1 = (h + S1 + ch + K[t] + w[t]) | 0;
                const S0 = ((a >>> 2) | (a << 30)) ^ ((a >>> 13) | (a << 19)) ^ ((a >>> 22) | (a << 10));
                const maj = (a & b) ^ (a & c) ^ (b & c);
                const temp2 = (S0 + maj) | 0;
                h = g; g = f; f = e; e = (d + temp1) | 0;
                d = c; c = b; b = a; a = (temp1 + temp2) | 0;
            }
            h0 = (h0 + a) | 0; h1 = (h1 + b) | 0; h2 = (h2 + c) | 0; h3 = (h3 + d) | 0;
            h4 = (h4 + e) | 0; h5 = (h5 + f) | 0; h6 = (h6 + g) | 0; h7 = (h7 + h) | 0;
        }

        const digest = new Uint8Array(32);
        const out = new DataView(digest.buffer);
        [h0, h1, h2, h3, h4, h5, h6, h7].forEach((word, i) => out.setUint32(i * 4, word >>> 0));
        return digest;
    }

    if (typeof module !== "undefined" && module.exports) module.exports = {sha256};
    else root.Sha256 = {sha256};

})(typeof self !== "undefined" ? self : this);
