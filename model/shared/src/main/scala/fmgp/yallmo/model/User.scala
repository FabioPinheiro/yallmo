package fmgp.yallmo.model

import zio.json.{JsonCodec, jsonNoExtraFields}

@jsonNoExtraFields
final case class User(id: UserId, secretHash: String) derives JsonCodec
