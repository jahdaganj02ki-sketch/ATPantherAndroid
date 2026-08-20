import { Client, Databases } from "node-appwrite";
import crypto from "node:crypto";

const queues = new Map();
const databaseId = process.env.LOCK_DATABASE_ID;
const collectionId = process.env.LOCK_COLLECTION_ID;
const sharedSecret = process.env.LOCK_SHARED_SECRET;
const ttlSeconds = 120;
const platforms = new Set(["android", "windows"]);

export default async ({ req, res, error }) => {
  try {
    const body = req.bodyJson ?? JSON.parse(req.body || "{}");
    if (!sharedSecret || !databaseId || !collectionId) {
      return res.json({ granted: false, error: "Lock service is not configured" }, 500);
    }
    if (!safeEqual(String(body.secret ?? ""), sharedSecret)) {
      return res.json({ granted: false, error: "Unauthorized" }, 401);
    }

    const operation = String(body.operation ?? "");
    const phoneHash = String(body.phoneHash ?? "");
    const deviceId = String(body.deviceId ?? "");
    const platform = String(body.platform ?? "");
    if (!/^[a-f0-9]{64}$/.test(phoneHash) || !deviceId ||
        !["select", "acquire", "heartbeat", "release"].includes(operation) ||
        !platforms.has(platform)) {
      return res.json({ granted: false, error: "Invalid request" }, 400);
    }

    const result = await enqueue(phoneHash, () =>
      handleLock(operation, phoneHash, deviceId, platform));
    return res.json(result, 200);
  } catch (exception) {
    error(exception?.stack ?? String(exception));
    return res.json({ granted: false, error: "Lock service error" }, 500);
  }
};

async function handleLock(operation, phoneHash, deviceId, platform) {
  const client = new Client()
    .setEndpoint(process.env.APPWRITE_FUNCTION_API_ENDPOINT ?? process.env.APPWRITE_ENDPOINT)
    .setProject(process.env.APPWRITE_FUNCTION_PROJECT_ID ?? process.env.APPWRITE_PROJECT_ID)
    .setKey(process.env.APPWRITE_API_KEY);
  const databases = new Databases(client);
  const now = Math.floor(Date.now() / 1000);
  const expiresAt = now + ttlSeconds;
  let document = null;

  try {
    document = await databases.getDocument(databaseId, collectionId, phoneHash);
  } catch (exception) {
    if (exception?.code !== 404) throw exception;
  }

  if (operation === "select") {
    const samePlatform = document?.activePlatform === platform;
    const data = {
      // A platform change invalidates the old lease immediately. Selecting the
      // same platform keeps an already running monitor alive.
      deviceId: samePlatform ? document.deviceId : "selector",
      expiresAt: samePlatform ? Number(document.expiresAt) : 0,
      updatedAt: now,
      activePlatform: platform,
    };
    if (document) {
      await databases.updateDocument(databaseId, collectionId, phoneHash, data);
    } else {
      await databases.createDocument(databaseId, collectionId, phoneHash, data);
    }
    return { granted: true, activePlatform: platform, expiresAt: data.expiresAt };
  }

  const activePlatform = document?.activePlatform ?? platform;
  if (activePlatform !== platform) {
    return { granted: false, reason: "selected_platform", activePlatform };
  }

  if (operation === "acquire") {
    if (document && document.deviceId !== deviceId && Number(document.expiresAt) > now) {
      return { granted: false, reason: "monitor_locked", activePlatform,
        expiresAt: Number(document.expiresAt) };
    }
    const data = { deviceId, expiresAt, updatedAt: now, activePlatform };
    if (!document) {
      try {
        await databases.createDocument(databaseId, collectionId, phoneHash, data);
      } catch (exception) {
        if (exception?.code === 409) return { granted: false, reason: "monitor_locked", activePlatform };
        throw exception;
      }
    } else {
      await databases.updateDocument(databaseId, collectionId, phoneHash, data);
    }
    return { granted: true, activePlatform, expiresAt };
  }

  if (!document || document.deviceId !== deviceId || Number(document.expiresAt) <= now) {
    return { granted: false, reason: "monitor_locked", activePlatform,
      expiresAt: document ? Number(document.expiresAt) : 0 };
  }

  if (operation === "heartbeat") {
    await databases.updateDocument(databaseId, collectionId, phoneHash, {
      deviceId,
      expiresAt,
      updatedAt: now,
      activePlatform,
    });
    return { granted: true, activePlatform, expiresAt };
  }

  await databases.updateDocument(databaseId, collectionId, phoneHash, {
    deviceId: "released",
    expiresAt: 0,
    updatedAt: now,
    activePlatform,
  });
  return { granted: true, activePlatform, expiresAt: 0 };
}

function enqueue(key, task) {
  const previous = queues.get(key) ?? Promise.resolve();
  const current = previous.catch(() => undefined).then(task);
  queues.set(key, current.finally(() => {
    if (queues.get(key) === current) queues.delete(key);
  }));
  return current;
}

function safeEqual(left, right) {
  const leftBuffer = Buffer.from(left);
  const rightBuffer = Buffer.from(right);
  return leftBuffer.length === rightBuffer.length && crypto.timingSafeEqual(leftBuffer, rightBuffer);
}
