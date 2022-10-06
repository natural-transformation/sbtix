import http.server
import socketserver
import base64

class AuthenticatedHTTPRequestHandler(http.server.SimpleHTTPRequestHandler):
  def do_GET(self):
    #print(self.request)
    if self.headers['Authorization']:
        if self.headers['Authorization'].startswith('Basic '):
            expected = base64.b64encode(bytes('test:test', 'utf-8')).decode()
            if self.headers['Authorization'][6:] == expected:
                http.server.SimpleHTTPRequestHandler.do_GET(self)
            else:
                self.send_error(400, "Basic auth, but wrong password")
        else:
            self.send_error(400, "No basic auth, but something else?")
    else:
        self.send_response(401, "Unauthorized")
        self.send_header('WWW-Authenticate', 'Basic realm="Very secret stuff!"')
        self.end_headers()

server = socketserver.TCPServer(("localhost", 9877), AuthenticatedHTTPRequestHandler)
server.serve_forever()
