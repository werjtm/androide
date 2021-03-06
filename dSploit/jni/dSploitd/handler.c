/* LICENSE
 * 
 * 
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <errno.h>
#include <string.h>
#include <dirent.h>
#include <dlfcn.h>
#include <unistd.h>
#include <stdlib.h>

#include "logger.h"
#include "list.h"
#include "handler.h"
#include "msgqueue.h"
#include "sequence.h"
#include "connection.h"
#include "control_messages.h"

list handlers;

/**
 * @brief handler sanity checks
 * @param h the handler to check
 * @returns 0 on success, -1 on error.
 */
int check_handler(handler *h) {
  char *test;
  int ret;
  
  if(!(h->enabled)) {
    print( WARNING, "handler '%s' disabled", h->name);
    return -1;
  }
  
  if(h->argv0) {
    if(h->workdir) {
      if(asprintf(&test, "%s/%s", h->workdir, h->argv0) == -1) {
        print( ERROR, "asprintf: %s", strerror(errno) );
        return -1;
      }
    } else {
      test = (char *) h->argv0;
    }
  } else if(h->workdir) {
    test = (char *) h->workdir;
  } else {
    return 0;
  }
  
  ret = access(test , X_OK);
  if(ret) {
    print( ERROR, "access(\"%s\", X_OK): %s", test, strerror(errno));
  }
    
  if(test != h->argv0 && test != h->workdir)
    free(test);
  
  return ret;
}

/**
 * @brief load all handlers found in the ::HANDLERS_DIR
 * @returns 0 on success, -1 on error.
 */
int load_handlers() {
  DIR *d;
  struct dirent *de;
  handler *h,*tmp;
  char *path;
  void *handle;
  size_t len;
  
  d = opendir(HANDLERS_DIR);
  
  if(!d) {
    print( ERROR, "opendir: %s", strerror(errno) );
    return -1;
  }
  
  while((de=readdir(d))) {
    if(!strncmp(de->d_name, ".", 2) || !strncmp(de->d_name, "..", 3)) {
      continue;
    }
    
    len = strlen(de->d_name);
    
    if(strncmp(de->d_name + (len - 3), ".so", 3))
      continue;
    
    if(asprintf(&path, HANDLERS_DIR "/%s", de->d_name) == -1) {
      print( ERROR, "asprintf: %s", strerror(errno) );
      continue;
    }
    
    if(!(handle = dlopen(path, RTLD_NOW))) {
      print( ERROR, "dlopen: %s", dlerror() );
      free(path);
      continue;
    }
    
    if(!(h = (handler *)dlsym(handle, "handler_info"))) {
      print( ERROR, "\"%s\": undefined reference to 'handler_info'", path );
      goto close;
    }
    
    if(check_handler(h)) {
      goto close;
    }
    
    h->dl_handle = handle;
    
    list_add(&(handlers), (node *) h);
    
    free(path);
    continue;
  
    close:
      
    if(dlclose(handle))
      print( ERROR, "dlclose(\"%s\"): %s", path, dlerror() );
    
    free(path);
  }
  
  closedir(d);
  
  if(!handlers.head) {
    print( ERROR, "no handlers found" );
    return -1;
  }
  
  for(h=(handler *) handlers.head;h->next;h=(handler *) h->next) {
    for(tmp=(handler *) h->next;tmp && tmp->id != h->id; tmp=(handler *) tmp->next);
    if(tmp) {
      print(ERROR, "\"%s\" and \"%s\" has the same id. (id=%d)\n",
              h->name, tmp->name, h->id);
      return -1;
    }
  }
  
  return 0;
}

/**
 * unload all handlers by calling dlclose and removing them from list.
 */
void unload_handlers() {
  handler *h;
  
  while((h=(handler *) queue_get(&(handlers)))) {
    if(dlclose(h->dl_handle))
      print( ERROR, "dlclose on \"%s\": %s", h->name, dlerror() );
  }
}

/**
 * @brief send handlers definitions
 * @param conn the ::connection to send these definitions
 * @returns 0 on success, -1 on error.
 */
int send_handlers_list(conn_node *conn) {
  handler *h;
  message *m;
  struct hndl_list_info *handlers_info;
  struct hndl_info *handler_info;
  int ret;
  size_t array_size;
  
  ret=-1;
  array_size=0;
  
  for(h=(handler *) handlers.head;h;h=(handler *) h->next) {
    array_size += sizeof(struct hndl_info);
    array_size += strlen(h->name) +1;
  }
  
  m = create_message(get_sequence(&(conn->ctrl_seq), &(conn->control.mutex)),
                     sizeof(struct hndl_list_info) + array_size,
                     CTRL_ID);
  
  if(!m) {
    print( ERROR, "cannot create messages" );
    goto exit;
  }
  
  handlers_info = (struct hndl_list_info *) m->data;
  handlers_info->hndl_code = HNDL_LIST;
  
  handler_info = handlers_info->list;
  
  for(h=(handler *) handlers.head;h;h=(handler *) h->next) {
    handler_info->id = h->id;
    handler_info->have_stdin = h->have_stdin;
    handler_info->have_stdout = h->have_stdout;
    
    strcpy(handler_info->name, h->name);
    
    handler_info = (struct hndl_info *) (
      ((char *) handler_info) + sizeof(struct hndl_info) + strlen(h->name) + 1);
  }
  
  if(enqueue_message(&(conn->outcoming), m)) {
    print( ERROR, "cannot enqueue message" );
    dump_message(m);
    free_message(m);
  } else {
    ret = 0;
  }
  
  exit:
  
  return ret;
}

int on_handler_request(conn_node *c, message *m) {
  
  switch(m->data[0]) {
    case HNDL_LIST:
      return send_handlers_list(c);
    default:
      print( ERROR, "unknown request code: %02hhX", m->data[0] );
      return -1;
  }
}
