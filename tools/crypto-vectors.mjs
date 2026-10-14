import crypto from 'node:crypto';
const password = 'synthetic-password-1';
const salt = 'c3ludGhldGljLXNhbHQ=';
const key = crypto.pbkdf2Sync(password, salt, 10000, 32, 'sha512');
const iv = Buffer.from('000102030405060708090a0b', 'hex');
const plaintext = Buffer.from('0a086163636f756e7473120161', 'hex');
const c = crypto.createCipheriv('aes-256-gcm', key, iv);
const data = Buffer.concat([c.update(plaintext), c.final()]);
const tag = c.getAuthTag();
console.log(JSON.stringify({password, salt, keyHex: key.toString('hex'), ivHex: iv.toString('hex'), plaintextHex: plaintext.toString('hex'), dataHex: data.toString('hex'), tagHex: tag.toString('hex')}, null, 1));
// unicode password
const k2 = crypto.pbkdf2Sync('pässwörd✓', 'salt', 10000, 32, 'sha512');
console.log('unicode', k2.toString('hex'));
