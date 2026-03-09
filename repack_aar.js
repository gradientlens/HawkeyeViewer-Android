const fs = require('fs');
const zlib = require('zlib');

const aarPath = 'libausbc/libs/libuvc-3.2.9.aar';
const backupPath = 'libausbc/libs/libuvc-3.2.9.aar.bak';

// Backup original
if (!fs.existsSync(backupPath)) {
    fs.copyFileSync(aarPath, backupPath);
    console.log('Backed up original AAR');
}

const buf = fs.readFileSync(aarPath);

// Parse all entries from the AAR
function parseZip(buf) {
    const entries = [];
    let offset = 0;
    while (offset < buf.length - 4) {
        const sig = buf.readUInt32LE(offset);
        if (sig !== 0x04034b50) break;
        const compMethod = buf.readUInt16LE(offset + 8);
        const modTime = buf.readUInt16LE(offset + 10);
        const modDate = buf.readUInt16LE(offset + 12);
        const crc32 = buf.readUInt32LE(offset + 14);
        const compSize = buf.readUInt32LE(offset + 18);
        const uncompSize = buf.readUInt32LE(offset + 22);
        const fnLen = buf.readUInt16LE(offset + 26);
        const exLen = buf.readUInt16LE(offset + 28);
        const name = buf.toString('utf8', offset + 30, offset + 30 + fnLen);
        const extra = buf.slice(offset + 30 + fnLen, offset + 30 + fnLen + exLen);
        const data = buf.slice(offset + 30 + fnLen + exLen, offset + 30 + fnLen + exLen + compSize);
        entries.push({ name, compMethod, modTime, modDate, crc32, compSize, uncompSize, extra, data });
        offset += 30 + fnLen + exLen + compSize;
    }
    return entries;
}

function buildZip(entries) {
    const parts = [];
    const cdEntries = [];
    let offset = 0;

    for (const e of entries) {
        const nameBuf = Buffer.from(e.name);
        const header = Buffer.alloc(30);
        header.writeUInt32LE(0x04034b50, 0);
        header.writeUInt16LE(20, 4);
        header.writeUInt16LE(0, 6);
        header.writeUInt16LE(e.compMethod, 8);
        header.writeUInt16LE(e.modTime || 0, 10);
        header.writeUInt16LE(e.modDate || 0, 12);
        header.writeUInt32LE(e.crc32, 14);
        header.writeUInt32LE(e.data.length, 18);
        header.writeUInt32LE(e.uncompSize, 22);
        header.writeUInt16LE(nameBuf.length, 26);
        header.writeUInt16LE(0, 28);

        const cd = Buffer.alloc(46);
        cd.writeUInt32LE(0x02014b50, 0);
        cd.writeUInt16LE(20, 4);
        cd.writeUInt16LE(20, 6);
        cd.writeUInt16LE(0, 8);
        cd.writeUInt16LE(e.compMethod, 10);
        cd.writeUInt16LE(e.modTime || 0, 12);
        cd.writeUInt16LE(e.modDate || 0, 14);
        cd.writeUInt32LE(e.crc32, 16);
        cd.writeUInt32LE(e.data.length, 20);
        cd.writeUInt32LE(e.uncompSize, 24);
        cd.writeUInt16LE(nameBuf.length, 28);
        cd.writeUInt16LE(0, 30);
        cd.writeUInt16LE(0, 32);
        cd.writeUInt16LE(0, 34);
        cd.writeUInt16LE(0, 36);
        cd.writeUInt32LE(0, 38);
        cd.writeUInt32LE(offset, 42);

        cdEntries.push(Buffer.concat([cd, nameBuf]));
        parts.push(header, nameBuf, e.data);
        offset += 30 + nameBuf.length + e.data.length;
    }

    const cdBuf = Buffer.concat(cdEntries);
    const eocd = Buffer.alloc(22);
    eocd.writeUInt32LE(0x06054b50, 0);
    eocd.writeUInt16LE(entries.length, 8);
    eocd.writeUInt16LE(entries.length, 10);
    eocd.writeUInt32LE(cdBuf.length, 12);
    eocd.writeUInt32LE(offset, 16);

    return Buffer.concat([...parts, cdBuf, eocd]);
}

// Parse the AAR
const aarEntries = parseZip(buf);

// Process classes.jar entry
for (let i = 0; i < aarEntries.length; i++) {
    if (aarEntries[i].name === 'classes.jar') {
        // Decompress jar if needed
        let jarBuf;
        if (aarEntries[i].compMethod === 0) {
            jarBuf = aarEntries[i].data;
        } else {
            jarBuf = zlib.inflateRawSync(aarEntries[i].data);
        }

        // Parse jar entries, remove com/jiangdg/usb/
        const jarEntries = parseZip(jarBuf);
        const filtered = jarEntries.filter(e => {
            if (e.name.startsWith('com/jiangdg/usb/')) {
                console.log('  Removing: ' + e.name);
                return false;
            }
            return true;
        });

        console.log('Removed ' + (jarEntries.length - filtered.length) + ' USB classes');

        // Rebuild jar
        const newJar = buildZip(filtered);

        // Update AAR entry (store uncompressed for simplicity)
        aarEntries[i].data = newJar;
        aarEntries[i].compMethod = 0;
        aarEntries[i].compSize = newJar.length;
        aarEntries[i].uncompSize = newJar.length;
        aarEntries[i].crc32 = zlib.crc32(newJar);
        break;
    }
}

// Rebuild AAR
const newAar = buildZip(aarEntries);
fs.writeFileSync(aarPath, newAar);
console.log('Repackaged AAR: ' + newAar.length + ' bytes (was ' + buf.length + ')');
