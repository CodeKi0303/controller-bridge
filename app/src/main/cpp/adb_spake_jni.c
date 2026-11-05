#include <jni.h>
#include <stdint.h>
#include <string.h>
#include <spake2/spake2.h>

/* Java owns context lifetime; all entry points are called under its monitor.
 * No logging, Java callbacks, or global secret/PRNG state. */
static void erase(void *value,size_t size){volatile uint8_t *p=value;while(size--)*p++=0;}
static struct spake2_ctx_st *context(jlong handle){return (struct spake2_ctx_st *)(uintptr_t)handle;}
JNIEXPORT jlong JNICALL Java_dev_codek_xboxprobe_AdbSpake_create(JNIEnv *env,jclass type,jboolean client,jbyteArray mine,jbyteArray theirs){
    (void)type;
    if(!mine||!theirs)return 0;
    jsize a=(*env)->GetArrayLength(env,mine),b=(*env)->GetArrayLength(env,theirs);
    if(a<1||a>128||b<1||b>128)return 0;
    uint8_t my_name[128],their_name[128];
    (*env)->GetByteArrayRegion(env,mine,0,a,(jbyte *)my_name);
    (*env)->GetByteArrayRegion(env,theirs,0,b,(jbyte *)their_name);
    if((*env)->ExceptionCheck(env))return 0;
    return (jlong)(uintptr_t)SPAKE2_CTX_new(client?spake2_role_alice:spake2_role_bob,my_name,(size_t)a,their_name,(size_t)b);
}
JNIEXPORT jbyteArray JNICALL Java_dev_codek_xboxprobe_AdbSpake_generate(JNIEnv *env,jclass type,jlong handle,jbyteArray password,jbyteArray random){
    (void)type;
    if(!handle||!password||!random)return NULL;
    jsize size=(*env)->GetArrayLength(env,password);
    if(size<1||size>1024||(*env)->GetArrayLength(env,random)!=64)return NULL;
    uint8_t secret[1024],entropy[64],message[32];size_t written=0;
    (*env)->GetByteArrayRegion(env,password,0,size,(jbyte *)secret);
    (*env)->GetByteArrayRegion(env,random,0,64,(jbyte *)entropy);
    int ok=!(*env)->ExceptionCheck(env)&&SPAKE2_generate_msg(context(handle),message,&written,sizeof(message),secret,(size_t)size,entropy);
    erase(secret,sizeof(secret));erase(entropy,sizeof(entropy));
    if(!ok||written!=32)return NULL;
    jbyteArray result=(*env)->NewByteArray(env,32);
    if(result)(*env)->SetByteArrayRegion(env,result,0,32,(jbyte *)message);
    return result;
}
JNIEXPORT jbyteArray JNICALL Java_dev_codek_xboxprobe_AdbSpake_process(JNIEnv *env,jclass type,jlong handle,jbyteArray peer){
    (void)type;
    if(!handle||!peer||(*env)->GetArrayLength(env,peer)!=32)return NULL;
    uint8_t message[32],key[64];size_t written=0;
    (*env)->GetByteArrayRegion(env,peer,0,32,(jbyte *)message);
    if((*env)->ExceptionCheck(env))return NULL;
    int ok=SPAKE2_process_msg(context(handle),key,&written,sizeof(key),message,sizeof(message));
    jbyteArray result=NULL;
    if(ok&&written==64){result=(*env)->NewByteArray(env,64);if(result)(*env)->SetByteArrayRegion(env,result,0,64,(jbyte *)key);}
    erase(key,sizeof(key));return result;
}
JNIEXPORT void JNICALL Java_dev_codek_xboxprobe_AdbSpake_destroy(JNIEnv *env,jclass type,jlong handle){
    (void)env;(void)type;SPAKE2_CTX_free(context(handle));
}
