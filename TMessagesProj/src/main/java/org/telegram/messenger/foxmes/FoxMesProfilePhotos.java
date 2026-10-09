package org.telegram.messenger.foxmes;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.tgnet.tl.TL_update;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.Vector;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;

final class FoxMesProfilePhotos {
    static class Photo {
        String id, url;
        @com.google.gson.annotations.SerializedName("file_id") long fileId;
        int width, height;
        @com.google.gson.annotations.SerializedName("is_main") boolean main;
    }
    static class State {
        List<Photo> photos;
        int count;
        @com.google.gson.annotations.SerializedName("photos_revision") long revision;
        FoxMesModels.User user;
    }
    static class Operation { String status; State result; }

    static TLRPC.Photo photo(Photo source) {
        if (source==null) return new TLRPC.TL_photoEmpty();
        FoxMesModels.User user=new FoxMesModels.User();
        user.avatarUrl=source.url;user.currentProfilePhotoId=source.id;
        return FoxMesTL.avatarPhoto(user,FoxMesHandlers.now());
    }
    private static java.util.Map<String,String> query(long maxId,int offset,int limit) {
        java.util.Map<String,String> values=new java.util.LinkedHashMap<>();values.put("max_id",Long.toString(maxId));values.put("limit",Integer.toString(limit));values.put("offset",Integer.toString(offset));return values;
    }
    static State read(FoxMesRuntime runtime,long userId,long maxId,int offset,int limit) throws IOException {
        return runtime.api.http.request("GET","/users/"+userId+"/photos",query(maxId,offset,limit),null,State.class);
    }
    static void apply(FoxMesRuntime runtime, State state) {
        if (state==null||state.user==null) return;
        runtime.rememberUser(state.user);
        FoxMesModels.User authoritative=runtime.knownUser(state.user.id);
        TLRPC.User user=FoxMesTL.user(authoritative,runtime);
        TL_update.TL_updateUser changed=new TL_update.TL_updateUser();changed.user_id=user.id;
        runtime.updates.dispatch(new ArrayList<>(Collections.singletonList(changed)),new ArrayList<>(Collections.singletonList(user)),null);
        AndroidUtilities.runOnUIThread(() -> {
            MessagesController.getInstance(runtime.account).putUser(user,false);
            if (user.id==runtime.selfId()) {UserConfig.getInstance(runtime.account).setCurrentUser(user);UserConfig.getInstance(runtime.account).saveConfig(true);}
            NotificationCenter.getInstance(runtime.account).postNotificationName(NotificationCenter.updateInterfaces,MessagesController.UPDATE_MASK_AVATAR);
        });
    }
    static State mutate(FoxMesRuntime runtime,String action,List<String> ids) throws IOException {
        FoxMesModels.User current=runtime.knownUser(runtime.selfId());
        String operation=FoxMesJson.operationId();
        FoxMesJson.Body body=FoxMesJson.body().put("operation_id",operation);
        if (current!=null&&current.photosRevision!=null) body.put("expected_revision",current.photosRevision);
        if ("delete".equals(action)) body.putStrings("photo_ids",ids);
        String path="delete".equals(action)?"/me/photos":"/me/photos/"+ids.get(0)+"/main";
        String method="delete".equals(action)?"DELETE":"PUT";
        try {return runtime.api.http.request(method,path,null,body,State.class);}
        catch(IOException failure) {
            if (failure instanceof FoxMesHttpException&&!((FoxMesHttpException)failure).isTransient()) throw failure;
            Operation journal=runtime.api.http.request("GET","/operations/"+operation,null,null,Operation.class);
            if ("done".equals(journal.status)&&journal.result!=null) return journal.result;
            if ("unknown".equals(journal.status)) return runtime.api.http.request(method,path,null,body,State.class);
            throw failure;
        }
    }
    static TLRPC.TL_photos_photo result(FoxMesRuntime runtime,State state) {
        apply(runtime,state);
        TLRPC.TL_photos_photo result=new TLRPC.TL_photos_photo();
        result.photo=new TLRPC.TL_photoEmpty();
        for(Photo item:FoxMesModels.list(state.photos)) if(item.main) {result.photo=photo(item);break;}
        if(state.user!=null) result.users.add(FoxMesTL.user(runtime.knownUser(state.user.id),runtime));
        return result;
    }
    static void invalidate(FoxMesRuntime runtime, com.google.gson.JsonObject data) {
        State state=FoxMesJson.parse(data,State.class);
        if(state==null||state.user==null) return;
        long id=state.user.id;
        AndroidUtilities.runOnUIThread(() -> {
            FoxMesModels.User current=runtime.knownUser(id);
            if(current!=null&&current.photosRevision!=null&&current.photosRevision>state.revision) return;
            MessagesController.DialogPhotos photos=MessagesController.getInstance(runtime.account).getDialogPhotos(id);
            photos.reset();
            photos.load(0,MessagesController.DialogPhotos.STEP);
        });
    }
    static void register(FoxMesTransport transport,FoxMesRuntime runtime) {
        transport.on(TLRPC.TL_photos_getUserPhotos.class,FoxMesTransport.Lane.IO,r -> {
            TLRPC.TL_photos_getUserPhotos request=(TLRPC.TL_photos_getUserPhotos)r.request;
            long userId=FoxMesHandlers.userId(request.user_id,runtime);
            State state=read(runtime,userId,request.max_id,request.offset,request.limit);
            apply(runtime,state);
            TLRPC.TL_photos_photosSlice response=new TLRPC.TL_photos_photosSlice();response.count=state.count;
            for(Photo item:FoxMesModels.list(state.photos)) response.photos.add(photo(item));
            if(state.user!=null) response.users.add(FoxMesTL.user(state.user,runtime));
            r.reply(response);
        });
        transport.on(TLRPC.TL_photos_uploadProfilePhoto.class,FoxMesTransport.Lane.SERIAL,r -> {
            TLRPC.TL_photos_uploadProfilePhoto request=(TLRPC.TL_photos_uploadProfilePhoto)r.request;
            if(request.file==null||request.video!=null||request.video_emoji_markup!=null) {r.fail(400,"PHOTO_INVALID");return;}
            File file=FoxMesFiles.assemble(runtime.account,request.file);
            String operation=FoxMesJson.operationId();
            try {
                MultipartBody body=new MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("operation_id",operation).addFormDataPart("file","avatar.jpg",RequestBody.create(file,MediaType.parse("image/jpeg"))).build();
                try {runtime.api.http.upload("POST","/upload/avatar",null,body,null,new String[1],120,null);}
                catch(IOException failure) {
                    Operation journal=runtime.api.http.request("GET","/operations/"+operation,null,null,Operation.class);
                    if(!"done".equals(journal.status)) throw failure;
                }
                r.reply(result(runtime,read(runtime,runtime.selfId(),0,0,100)));
            } finally {file.delete();}
        });
        transport.on(TLRPC.TL_photos_updateProfilePhoto.class,FoxMesTransport.Lane.SERIAL,r -> {
            TLRPC.TL_photos_updateProfilePhoto request=(TLRPC.TL_photos_updateProfilePhoto)r.request;
            String id=request.id instanceof TLRPC.TL_inputPhotoEmpty ? (r.profilePhotoAtSend>0?Long.toString(r.profilePhotoAtSend):null):Long.toString(request.id.id);
            if(id==null||id.isEmpty()) {r.fail(400,"PHOTO_ID_INVALID");return;}
            try {r.reply(result(runtime,mutate(runtime,request.id instanceof TLRPC.TL_inputPhotoEmpty?"delete":"main",Collections.singletonList(id))));}
            catch(IOException failure) {apply(runtime,read(runtime,runtime.selfId(),0,0,100));throw failure;}
        });
        transport.on(TLRPC.TL_photos_deletePhotos.class,FoxMesTransport.Lane.SERIAL,r -> {
            TLRPC.TL_photos_deletePhotos request=(TLRPC.TL_photos_deletePhotos)r.request;
            ArrayList<String> ids=new ArrayList<>();for(TLRPC.InputPhoto p:request.id) ids.add(Long.toString(p.id));
            try {apply(runtime,mutate(runtime,"delete",ids));MessagesStorage.getInstance(runtime.account).clearUserPhotos(runtime.selfId());Vector<Vector.Long> response=new Vector<>(Vector.Long::TLDeserialize);for(String id:ids) {long value=Long.parseLong(id);response.objects.add(new Vector.Long((int)(value >>> 32),(int)value));}r.reply(response);}
            catch(IOException failure) {apply(runtime,read(runtime,runtime.selfId(),0,0,100));throw failure;}
        });
    }
}
