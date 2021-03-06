#include <string.h>
#include <regex.h>

#include "handler.h"
#include "logger.h"
#include "arpspoof.h"
#include "message.h"

handler handler_info = {
  NULL,                       // next
  5,                          // handler id
  0,                          // have_stdin
  1,                          // have_stdout
  1,                          // enabled
  NULL,                       // raw_output_parser
  &arpspoof_output_parser,    // output_parser
  NULL,                       // input_parser
  "tools/arpspoof/arpspoof",  // argv[0]
  NULL,                       // workdir
  "arpspoof"                  // handler name
};

regex_t error_pattern;

__attribute__((constructor))
void arpspoof_init() {
  int ret;
  
  if((ret = regcomp(&error_pattern, "^\\[ERROR\\] ", REG_ICASE))) {
    print(ERROR, "regcomp(error_pattern): %d", ret);
  }
}

__attribute__((destructor))
void arpspoof_fini() {
  regfree(&error_pattern);
}

/**
 * @brief search for error messages and report them back if present
 * @param line the line to parse
 * @returns a message to send or NULL
 */
message *arpspoof_output_parser(char *line) {
  message *m;
  size_t len;
  
  len = strlen(line);
  
  if(!len) {
    return NULL;
  }
  
  m = NULL;
  
  if(regexec(&error_pattern, line, 0, NULL, 0))
    return NULL;
  
  // isn't so readable, but is faster
  
  m = create_message(0, (len - 6), 0);
  
  if(!m) {
    print(ERROR, "cannot create messages");
    return NULL;
  }
  
  m->data[0] = ARPSPOOF_ERROR;
  memcpy(m->data + 1, line + 8, len - 7);
  
  return m;
}
