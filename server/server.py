# ---Создал Bogdan2248---
import asyncio
import io
import os
from quart import Quart, request, jsonify, send_file
from telethon import TelegramClient, events
from telethon.tl.types import PeerUser, PeerChat, PeerChannel, MessageMediaPhoto
from PIL import Image
import config

app = Quart(__name__)

# Клиент Telegram
client = None
client_lock = asyncio.Lock()

async def get_client():
    global client
    async with client_lock:
        if client is None:
            print("[***] Initializing TelegramClient...")
            client = TelegramClient('session_name', config.API_ID, config.API_HASH)
        
        if not client.is_connected():
            print("[***] Connecting to Telegram...")
            try:
                await client.connect()
                print("[***] Connected!")
            except Exception as e:
                print(f"[!!!] Connection failed: {e}")
                # Если сессия повреждена, можно попробовать пересоздать клиент
                if "database is locked" in str(e).lower():
                    client = TelegramClient('session_name', config.API_ID, config.API_HASH)
                    await client.connect()
        
        # Проверка на дисконнект в процессе работы
        try:
            if not await client.is_user_authorized():
                pass # Это нормально для неавторизованных
        except Exception as e:
            if "not connected" in str(e).lower():
                print("[***] Client disconnected unexpectedly, reconnecting...")
                await client.connect()
                
        return client

@app.before_serving
async def startup():
    await get_client()
    print("[***] Server startup complete, client connected.")

@app.after_serving
async def shutdown():
    if client and client.is_connected():
        await client.disconnect()
        print("[***] Client disconnected.")

@app.before_request
async def log_request_info():
    print(f"\n[>>>] Incoming {request.method} request to {request.path}")
    print(f"      From IP: {request.remote_addr}")
    print(f"      User-Agent: {request.headers.get('User-Agent')}")

@app.after_request
async def add_header(response):
    response.headers["Connection"] = "close"
    response.headers["bypass-tunnel-reminder"] = "true"
    return response

@app.route('/')
async def index():
    return "Server is RUNNING and REACHABLE (Quart)!"

@app.route('/api/chats', methods=['GET'])
@app.route('/chats', methods=['GET'])
async def get_chats():
    try:
        c = await get_client()
        if not await c.is_user_authorized():
            return jsonify({"status": "unauthorized"})
        
        dialogs = await c.get_dialogs(limit=20)
        result = []
        for d in dialogs:
            result.append({
                "id": str(d.id),
                "name": d.name or "Unknown",
                "unread": d.unread_count
            })
        return jsonify(result)
    except Exception as e:
        print(f"[!!!] Error in get_chats: {e}")
        return jsonify({"status": "error", "message": str(e)})

@app.route('/api/messages', methods=['GET'])
@app.route('/messages', methods=['GET'])
async def get_messages():
    try:
        chat_id = int(request.args.get('id'))
        c = await get_client()
        if not await c.is_user_authorized():
            return jsonify({"status": "unauthorized"})
        
        messages = await c.get_messages(chat_id, limit=20)
        result = []
        for m in messages:
            if m.out:
                sender_name = "Me"
            else:
                sender = await m.get_sender()
                sender_name = getattr(sender, 'first_name', 'System') or "System"
            text = m.text or ""
            
            if m.media:
                from telethon.tl.types import MessageMediaPhoto, MessageMediaDocument, MessageMediaGeo, MessageMediaContact
                if isinstance(m.media, MessageMediaPhoto):
                    if not text: text = "[Фотография]"
                elif m.video_note: text = "[Кружок (Video Note)]" + (" " + text if text else "")
                elif m.voice: text = "[Голосовое сообщение]" + (" " + text if text else "")
                elif m.video: text = "[Видео]" + (" " + text if text else "")
                elif m.audio: text = "[Аудио]" + (" " + text if text else "")
                elif isinstance(m.media, MessageMediaDocument):
                    text = "[Файл: " + (m.file.name or "document") + "]" + (" " + text if text else "")
                elif isinstance(m.media, MessageMediaGeo): text = "[Локация]"
                elif isinstance(m.media, MessageMediaContact): text = "[Контакт: " + m.media.first_name + "]"
                else:
                    if not text: text = "[Медиа сообщение]"

            msg_data = {
                "id": str(m.id),
                "from": sender_name,
                "text": text,
                "has_photo": 1 if isinstance(m.media, MessageMediaPhoto) else 0,
                "reactions": ""
            }
            
            if m.reactions:
                reac_list = []
                for r in m.reactions.results:
                    try:
                        emo = getattr(r.reaction, 'emoticon', '')
                        if emo: reac_list.append(emo + ":" + str(r.count))
                    except: continue
                msg_data["reactions"] = " ".join(reac_list)
            
            if m.is_reply:
                reply_to = await m.get_reply_message()
                if reply_to:
                    msg_data["reply_to"] = (reply_to.text[:20] + "...") if reply_to.text else "Media"

            if msg_data["text"] or msg_data["has_photo"]:
                result.append(msg_data)
                
        return jsonify(result[::-1])
    except Exception as e:
        print(f"[!!!] Error in get_messages: {e}")
        return jsonify({"status": "error", "message": str(e)})

@app.route('/api/photo', methods=['GET'])
@app.route('/photo', methods=['GET'])
async def get_photo():
    chat_id = int(request.args.get('chat_id'))
    msg_id = int(request.args.get('msg_id'))
    c = await get_client()
    msg = await c.get_messages(chat_id, ids=msg_id)
    if not msg or not msg.photo: return "No photo", 404
    
    photo_bytes = await c.download_media(msg.photo, file=bytes)
    img = Image.open(io.BytesIO(photo_bytes))
    ratio = 240 / float(img.size[0])
    height = int(float(img.size[1]) * float(ratio))
    img = img.resize((240, height), Image.Resampling.LANCZOS)
    
    img_io = io.BytesIO()
    img.save(img_io, 'JPEG', quality=60)
    img_io.seek(0)
    return await send_file(img_io, mimetype='image/jpeg')

@app.route('/api/send', methods=['GET', 'POST'])
@app.route('/send', methods=['GET', 'POST'])
async def send_message():
    try:
        chat_id = int(request.args.get('id'))
        text = request.args.get('text')
        
        # Теперь используем стандартный unquote, так как клиент шлет UTF-8
        import urllib.parse
        text = urllib.parse.unquote(text) if text else ""

        print(f"[>>>] Sending message to {chat_id}: {text}")
        c = await get_client()
        if not await c.is_user_authorized(): return jsonify({"status": "unauthorized"})
        
        if request.method == 'POST':
            # Обработка загрузки фото
            files = await request.files
            if 'photo' in files:
                photo = files['photo']
                photo_bytes = photo.read()
                await c.send_file(chat_id, photo_bytes, caption=text)
                return jsonify({"status": "ok"})
        
        await c.send_message(chat_id, text)
        return jsonify({"status": "ok"})
    except Exception as e:
        print(f"[!!!] Error sending message: {e}")
        return jsonify({"status": "error", "message": str(e)})

@app.route('/upload', methods=['POST'])
async def upload_photo():
    try:
        chat_id = int(request.args.get('id'))
        files = await request.files
        if 'photo' not in files:
            return jsonify({"status": "error", "message": "No photo"}), 400
        
        photo = files['photo']
        photo_bytes = photo.read()
        
        c = await get_client()
        await c.send_file(chat_id, photo_bytes)
        return "OK"
    except Exception as e:
        print(f"[!!!] Error uploading photo: {e}")
        return str(e), 500

@app.route('/api/react', methods=['GET'])
@app.route('/react', methods=['GET'])
async def react_message():
    chat_id = int(request.args.get('chat_id'))
    msg_id = int(request.args.get('msg_id'))
    emoji = request.args.get('emoji')
    c = await get_client()
    if not await c.is_user_authorized(): return jsonify({"status": "unauthorized"})
    
    try:
        from telethon.tl.functions.messages import SendReactionRequest
        from telethon.tl.types import ReactionEmoji
        await c(SendReactionRequest(peer=chat_id, msg_id=msg_id, reaction=[ReactionEmoji(emoticon=emoji)]))
        return jsonify({"status": "ok"})
    except Exception as e: return jsonify({"status": "error", "message": str(e)})

# Глобальное состояние для кода подтверждения
phone_code_hashes = {}

@app.route('/api/auth/send_code', methods=['GET'])
@app.route('/auth/send_code', methods=['GET'])
async def send_code():
    phone = request.args.get('phone')
    if not phone: return "No phone", 400
    phone = phone.replace(" ", "").replace("-", "")
    print(f"\n[!] incoming request from phone: {phone}")
    try:
        c = await get_client()
        res = await c.send_code_request(phone)
        phone_code_hashes[phone] = res.phone_code_hash
        return jsonify({"status": "ok", "message": "Code sent"})
    except Exception as e: return jsonify({"status": "error", "message": str(e)})

@app.route('/api/auth/login', methods=['GET'])
@app.route('/auth/login', methods=['GET'])
async def login():
    phone = request.args.get('phone')
    code = request.args.get('code')
    password = request.args.get('password')
    c = await get_client()
    from telethon.errors import SessionPasswordNeededError, PhoneCodeInvalidError, PhoneCodeExpiredError
    try:
        if phone in phone_code_hashes:
            try:
                await c.sign_in(phone, code, phone_code_hash=phone_code_hashes[phone])
                return jsonify({"status": "ok", "message": "Logged in"})
            except SessionPasswordNeededError:
                if password:
                    await c.sign_in(password=password)
                    return jsonify({"status": "ok", "message": "Logged in with 2FA"})
                else: return jsonify({"status": "error", "message": "2FA_REQUIRED"})
            except PhoneCodeInvalidError: return jsonify({"status": "error", "message": "INVALID_CODE"})
            except PhoneCodeExpiredError: return jsonify({"status": "error", "message": "CODE_EXPIRED"})
        else:
            try:
                await c.sign_in(phone, code)
                return jsonify({"status": "ok", "message": "Logged in (no hash)"})
            except Exception: return jsonify({"status": "error", "message": "NO_HASH_OR_EXPIRED"})
    except Exception as e: return jsonify({"status": "error", "message": str(e)})

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=config.PORT, debug=True)
